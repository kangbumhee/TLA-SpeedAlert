package com.teslcan.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class RouteSimulator {

    companion object {
        private const val TAG = "RouteSim"
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    data class SimPoint(val lat: Double, val lon: Double, val bearing: Double, val speedKmh: Int)

    private var routePoints = mutableListOf<SimPoint>()
    private var currentIndex = 0
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    var onLocationUpdate: ((lat: Double, lon: Double, speedKmh: Int, sats: Int, fix: Boolean, bearingDeg: Double) -> Unit)? = null
    var onSimulationEnd: (() -> Unit)? = null
    var onRouteReady: ((pointCount: Int, distanceKm: Double) -> Unit)? = null
    var speedMultiplier = 1.0

    fun startRoute(startLat: Double, startLon: Double, endLat: Double, endLon: Double, speedKmh: Int = 60) {
        stop()
        if (!RouteService.hasRoutingProvider()) {
            Log.e(TAG, "경로 API 없음: USE_MAPBOX+토큰 또는 OSRM_ROUTE_BASE_URL 설정 필요")
            Log.w(TAG, "fallback: 직선 경로 생성")
            val fallback = generateFallbackRoute(startLat, startLon, endLat, endLon, speedKmh)
            routePoints = fallback
            currentIndex = 0
            isRunning = true
            handler.post {
                onRouteReady?.invoke(routePoints.size, 0.0)
                handler.post(tickRunnable)
            }
            return
        }
        Thread {
            Log.d(TAG, "경로 API 요청 (Mapbox/OSRM)")
            val points = fetchRoute(startLon, startLat, endLon, endLat)
            if (points.isEmpty()) {
                Log.e(TAG, "경로 로딩 실패 → fallback 직선 경로")
                val fallback = generateFallbackRoute(startLat, startLon, endLat, endLon, speedKmh)
                handler.post {
                    routePoints = fallback
                    currentIndex = 0
                    isRunning = true
                    onRouteReady?.invoke(routePoints.size, 0.0)
                    handler.post(tickRunnable)
                }
                return@Thread
            }
            routePoints = interpolatePoints(points, speedKmh)
            currentIndex = 0
            isRunning = true
            val totalDist = calcTotalDistance(points)
            Log.d(TAG, "✓ 경로: ${points.size}좌표 -> ${routePoints.size}보간, ${"%.1f".format(totalDist / 1000)}km")
            handler.post {
                onRouteReady?.invoke(routePoints.size, totalDist / 1000.0)
                handler.post(tickRunnable)
            }
        }.start()
    }

    fun startPreset(name: String, speedKmh: Int = 60) {
        when (name) {
            "gangnam_jamsil" -> startRoute(37.497952, 127.027619, 37.513950, 127.102102, speedKmh)
            "seocho_yangjae" -> startRoute(37.491912, 127.007578, 37.484100, 127.034000, speedKmh)
            "yeongdeungpo_yeouido" -> startRoute(37.515836, 126.907299, 37.521600, 126.924300, speedKmh)
            "jongro_dongdaemun" -> startRoute(37.570100, 126.982600, 37.571400, 127.009800, speedKmh)
            "camera_dense" -> startRoute(37.482661, 127.012154, 37.531602, 127.149541, speedKmh)
            else -> Log.e(TAG, "알 수 없는 프리셋: $name")
        }
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        routePoints.clear()
        currentIndex = 0
    }

    fun isActive(): Boolean = isRunning

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || currentIndex >= routePoints.size) {
                isRunning = false
                Log.d(TAG, "시뮬레이션 종료")
                onSimulationEnd?.invoke()
                return
            }
            val p = routePoints[currentIndex]
            onLocationUpdate?.invoke(p.lat, p.lon, p.speedKmh, 12, true, p.bearing)
            currentIndex++
            val interval = (UPDATE_INTERVAL_MS / speedMultiplier).toLong().coerceAtLeast(100L)
            handler.postDelayed(this, interval)
        }
    }

    private fun fetchRoute(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): List<DoubleArray> {
        val pts = RouteService.fetchRouteCoordinates(fromLon, fromLat, toLon, toLat)
        if (pts.isNotEmpty()) Log.d(TAG, "실제 도로: ${pts.size}좌표")
        return pts
    }

    private fun generateFallbackRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        speedKmh: Int
    ): MutableList<SimPoint> {
        val points = mutableListOf<SimPoint>()
        val dist = haversine(startLat, startLon, endLat, endLon)
        val bearing = calcBearing(startLat, startLon, endLat, endLon)
        val metersPerTick = speedKmh / 3.6
        val steps = (dist / metersPerTick).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val ratio = i.toDouble() / steps
            val lat = startLat + (endLat - startLat) * ratio
            val lon = startLon + (endLon - startLon) * ratio
            points.add(SimPoint(lat, lon, bearing, speedKmh))
        }
        Log.w(TAG, "fallback 직선 경로: ${points.size}포인트, ${"%.0f".format(dist)}m")
        return points
    }

    private fun interpolatePoints(original: List<DoubleArray>, speedKmh: Int): MutableList<SimPoint> {
        val result = mutableListOf<SimPoint>()
        if (original.size < 2) return result
        val metersPerTick = speedKmh / 3.6
        var segIdx = 0
        var segProgress = 0.0

        while (segIdx < original.size - 1) {
            val from = original[segIdx]
            val to = original[segIdx + 1]
            val segLen = haversine(from[0], from[1], to[0], to[1])
            if (segLen <= 0.0) {
                segIdx++
                continue
            }
            val bearing = calcBearing(from[0], from[1], to[0], to[1])

            while (segProgress < segLen) {
                val ratio = segProgress / segLen
                val lat = from[0] + (to[0] - from[0]) * ratio
                val lon = from[1] + (to[1] - from[1]) * ratio

                val nextBearing = if (segIdx + 2 < original.size) {
                    calcBearing(to[0], to[1], original[segIdx + 2][0], original[segIdx + 2][1])
                } else {
                    bearing
                }
                val aDiff = angleDiff(bearing, nextBearing)
                val curveSpeed = when {
                    aDiff > 60.0 -> (speedKmh * 0.5).toInt()
                    aDiff > 30.0 -> (speedKmh * 0.7).toInt()
                    aDiff > 15.0 -> (speedKmh * 0.85).toInt()
                    else -> speedKmh
                }
                result.add(SimPoint(lat, lon, bearing, curveSpeed))
                segProgress += metersPerTick
            }
            segProgress -= segLen
            segIdx++
        }

        val last = original.last()
        val prev = original[original.size - 2]
        result.add(SimPoint(last[0], last[1], calcBearing(prev[0], prev[1], last[0], last[1]), 0))
        return result
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calcBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun calcTotalDistance(pts: List<DoubleArray>): Double {
        var total = 0.0
        for (i in 0 until pts.size - 1) {
            total += haversine(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
        }
        return total
    }
}
