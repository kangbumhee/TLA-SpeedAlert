package com.teslcan.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class RouteSimulator {

    companion object {
        private const val TAG = "RouteSim"
        private const val SCENARIO_TICK_SEC = 1.0

        fun scenarioDisplayName(preset: String): String = when (preset) {
            "scenario_basic" -> "기본 주행 (실도로)"
            "scenario_dense", "camera_dense" -> "카메라 밀집 (실도로)"
            "scenario_highway" -> "고속 구간 (실도로)"
            "scenario_janggi_gochon" -> "장기동→고촌IC (김포)"
            "gangnam_jamsil" -> "강남→잠실 (OSRM)"
            "seocho_yangjae" -> "서초→양재 (OSRM)"
            "yeongdeungpo_yeouido" -> "영등포→여의도 (OSRM)"
            "jongro_dongdaemun" -> "종로→동대문 (OSRM)"
            else -> preset
        }

        /** 설정·디버그용: 스피너/버튼 인덱스 → 프리셋 id */
        fun scenarioPresetAt(index: Int): String? = when (index) {
            0 -> "scenario_basic"
            1 -> "scenario_dense"
            2 -> "scenario_highway"
            3 -> "scenario_janggi_gochon"
            else -> null
        }

        fun scenarioNameAt(index: Int): String =
            scenarioPresetAt(index)?.let { scenarioDisplayName(it) } ?: "알 수 없음"

        fun allEmbeddedPresetIds(): List<String> = listOf(
            "scenario_basic",
            "scenario_dense",
            "scenario_highway",
            "scenario_janggi_gochon"
        )
    }

    data class SimPoint(val lat: Double, val lon: Double, val bearing: Double, val speedKmh: Int)

    private var routePoints = mutableListOf<SimPoint>()
    private var currentIndex = 0
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    var onLocationUpdate: ((lat: Double, lon: Double, speedKmh: Int, sats: Int, fix: Boolean, bearingDeg: Double) -> Unit)? = null
    var onSimulationEnd: (() -> Unit)? = null
    var onRouteReady: ((pointCount: Int, distanceKm: Double) -> Unit)? = null

    /**
     * 재생 속도 배율 (틱 간격만 조절, 웨이포인트 표기 속도는 그대로).
     * 1.0 = 1초마다 한 포인트, 2.0 = 약 0.5초마다.
     */
    var speedMultiplier: Double = 1.0
        set(value) {
            field = value.coerceIn(0.25, 5.0)
        }

    fun getTickIntervalMs(): Long =
        (1000.0 / speedMultiplier).toLong().coerceIn(100L, 4000L)

    /** 시뮬 중 배율 변경 시 다음 틱 스케줄을 즉시 반영 */
    fun notifyTickIntervalChanged() {
        if (!isRunning) return
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

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

    /**
     * UI 스피너 기준 속도(기본 60)에 맞춰 시나리오 구간 속도를 스케일.
     * 실도로 웨이포인트 → 1초 간격 보간 + 이전→현재 bearing.
     */
    fun startPreset(name: String, speedKmh: Int = 60) {
        when (name) {
            "scenario_basic" -> startEmbeddedScenario(ScenarioWaypoints.BASIC, speedKmh, "scenario_basic")
            "scenario_dense", "camera_dense" ->
                startEmbeddedScenario(ScenarioWaypoints.DENSE, speedKmh, "scenario_dense")
            "scenario_highway" -> startEmbeddedScenario(ScenarioWaypoints.HIGHWAY, speedKmh, "scenario_highway")
            "scenario_janggi_gochon" ->
                startEmbeddedScenario(ScenarioWaypoints.JANGGI_GOCHON, speedKmh, "scenario_janggi_gochon")
            "gangnam_jamsil" -> startRoute(37.497952, 127.027619, 37.513950, 127.102102, speedKmh)
            "seocho_yangjae" -> startRoute(37.491912, 127.007578, 37.484100, 127.034000, speedKmh)
            "yeongdeungpo_yeouido" -> startRoute(37.515836, 126.907299, 37.521600, 126.924300, speedKmh)
            "jongro_dongdaemun" -> startRoute(37.570100, 126.982600, 37.571400, 127.009800, speedKmh)
            else -> Log.e(TAG, "알 수 없는 프리셋: $name")
        }
    }

    private fun startEmbeddedScenario(waypoints: List<ScenarioWp>, speedKmhFromUi: Int, label: String) {
        stop()
        if (waypoints.size < 2) {
            Log.e(TAG, "웨이포인트 부족: $label")
            return
        }
        val scale = speedKmhFromUi / 60.0
        routePoints = expandWaypointsToSimPath(waypoints, SCENARIO_TICK_SEC, scale)
        currentIndex = 0
        isRunning = true
        val km = pathLengthMeters(routePoints) / 1000.0
        Log.d(TAG, "실도로 시뮬 [$label]: 원본 ${waypoints.size}wp → ${routePoints.size}틱, ${"%.1f".format(km)}km")
        handler.post {
            onRouteReady?.invoke(routePoints.size, km)
            handler.post(tickRunnable)
        }
    }

    private fun pathLengthMeters(pts: List<SimPoint>): Double {
        if (pts.size < 2) return 0.0
        var t = 0.0
        for (i in 0 until pts.size - 1) {
            t += haversine(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        return t
    }

    private fun expandWaypointsToSimPath(
        wps: List<ScenarioWp>,
        intervalSec: Double,
        speedScale: Double
    ): MutableList<SimPoint> {
        val samples = mutableListOf<Triple<Double, Double, Int>>()
        for (i in 0 until wps.size - 1) {
            val from = wps[i]
            val to = wps[i + 1]
            val speedKmh = (from.speedKmh * speedScale).coerceIn(10.0, 130.0)
            val speedMps = speedKmh / 3.6
            val segDist = haversine(from.lat, from.lon, to.lat, to.lon)
            if (segDist < 0.5) continue
            val segTime = segDist / speedMps
            val steps = (segTime / intervalSec).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val frac = s.toDouble() / steps
                val lat = from.lat + (to.lat - from.lat) * frac
                val lon = from.lon + (to.lon - from.lon) * frac
                samples.add(Triple(lat, lon, speedKmh.roundToInt()))
            }
        }
        val last = wps.last()
        val lastSpeed = (last.speedKmh * speedScale).coerceIn(10.0, 130.0).roundToInt()
        samples.add(Triple(last.lat, last.lon, lastSpeed))

        val out = mutableListOf<SimPoint>()
        for (i in samples.indices) {
            val (la, lo, sp) = samples[i]
            val br = when {
                i < samples.size - 1 ->
                    calcBearing(la, lo, samples[i + 1].first, samples[i + 1].second)
                i > 0 ->
                    calcBearing(samples[i - 1].first, samples[i - 1].second, la, lo)
                else ->
                    calcBearing(la, lo, samples[i + 1].first, samples[i + 1].second)
            }
            out.add(SimPoint(la, lo, br, sp))
        }
        return out
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
            handler.postDelayed(this, getTickIntervalMs())
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
