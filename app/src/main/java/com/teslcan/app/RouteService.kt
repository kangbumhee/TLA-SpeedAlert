package com.teslcan.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import kotlin.math.roundToInt
import java.net.URL
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Mapbox Directions v5와 OSRM `/route/v1/driving` 응답이 동일 계열이므로 한 파서로 처리.
 * [BuildConfig.USE_MAPBOX] + 토큰 → Mapbox, 아니면 [BuildConfig.OSRM_ROUTE_BASE_URL] → OSRM(HTTPS 권장).
 */
object RouteService {

    private const val TAG = "RouteService"
    private const val MAPBOX_BASE = "https://api.mapbox.com/directions/v5/mapbox/driving"
    private const val TIMEOUT = 15_000
    private const val USER_AGENT = "TLA-SpeedAlert/1.0"

    data class LatLon(val lat: Double, val lon: Double)

    data class RouteResult(
        val roadDistance: Double,
        val straightDistance: Double,
        val routePoints: List<LatLon>,
        val success: Boolean
    )

    fun hasRoutingProvider(): Boolean {
        if (BuildConfig.USE_MAPBOX) {
            val t = BuildConfig.MAPBOX_ACCESS_TOKEN
            return t.isNotBlank() && t != "YOUR_MAPBOX_PUBLIC_TOKEN"
        }
        return BuildConfig.OSRM_ROUTE_BASE_URL.isNotBlank()
    }

    /** 시뮬 등: 좌표열 [lat, lon] */
    fun fetchRouteCoordinates(
        fromLon: Double,
        fromLat: Double,
        toLon: Double,
        toLat: Double
    ): List<DoubleArray> {
        val r = getRoute(fromLat, fromLon, toLat, toLon, 0.0)
        if (!r.success || r.routePoints.isEmpty()) return emptyList()
        return r.routePoints.map { doubleArrayOf(it.lat, it.lon) }
    }

    fun getRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        straightDist: Double
    ): RouteResult {
        val url = buildUrl(fromLon, fromLat, toLon, toLat) ?: return fallback(straightDist)
        return executeRequest(url, straightDist)
    }

    /**
     * OSRM Nearest: 카메라 좌표를 차량 진행 방향에 맞는 도로 세그먼트에 스냅할 수 있는지.
     * [bearings]=`방위,허용각` — heading 미상 카메라의 반대차선·측면 오탐 완화용. Mapbox 전용 빌드에서는 true.
     * 네트워크/파싱 실패 시 true(후보 유지).
     */
    fun nearestSnapAcceptsBearing(
        camLat: Double,
        camLon: Double,
        vehicleBearingDeg: Double,
        bearingSpreadDeg: Int = 45
    ): Boolean {
        if (BuildConfig.USE_MAPBOX) return true
        val nearestBase = buildOsrmNearestServiceBase() ?: return true
        val br = ((vehicleBearingDeg % 360.0) + 360.0) % 360.0
        val brInt = ((br.roundToInt() % 360) + 360) % 360
        val url = "$nearestBase/$camLon,$camLat?number=1&bearings=$brInt,$bearingSpreadDeg"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val http = conn.responseCode
            if (http != 200) {
                Log.w(TAG, "nearest HTTP $http URL=${urlForLog(url)}")
                conn.disconnect()
                return true
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(body)
            val resultCode = obj.optString("code", "")
            if (resultCode != "Ok") {
                Log.d(TAG, "nearest code=$resultCode (${urlForLog(url)})")
                return false
            }
            val wps = obj.optJSONArray("waypoints")
            wps != null && wps.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "nearest 실패: ${e.javaClass.simpleName}: ${e.message} URL=${urlForLog(url)}")
            true
        }
    }

    /** `.../route/v1/driving` → `.../nearest/v1/driving` */
    private fun buildOsrmNearestServiceBase(): String? {
        val raw = BuildConfig.OSRM_ROUTE_BASE_URL.trim().trimEnd('/')
        if (raw.isBlank()) return null
        val replaced = raw.replace("/route/v1/", "/nearest/v1/")
        if (replaced == raw || !replaced.contains("/nearest/v1/")) {
            Log.w(TAG, "OSRM URL에 /route/v1/ 없음 — nearest 생략")
            return null
        }
        return replaced
    }

    private fun buildUrl(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): String? {
        val coords = "$fromLon,$fromLat;$toLon,$toLat"
        val query = "geometries=geojson&overview=full"
        return when {
            BuildConfig.USE_MAPBOX -> {
                val t = BuildConfig.MAPBOX_ACCESS_TOKEN
                if (t.isBlank() || t == "YOUR_MAPBOX_PUBLIC_TOKEN") {
                    Log.w(TAG, "USE_MAPBOX 이지만 토큰 없음")
                    null
                } else {
                    "$MAPBOX_BASE/$coords?$query&access_token=$t"
                }
            }
            BuildConfig.OSRM_ROUTE_BASE_URL.isNotBlank() -> {
                val base = BuildConfig.OSRM_ROUTE_BASE_URL.trimEnd('/')
                "$base/$coords?$query"
            }
            else -> {
                val t = BuildConfig.MAPBOX_ACCESS_TOKEN
                if (t.isNotBlank() && t != "YOUR_MAPBOX_PUBLIC_TOKEN") {
                    "$MAPBOX_BASE/$coords?$query&access_token=$t"
                } else {
                    Log.w(TAG, "OSRM URL·Mapbox 토큰 모두 없음")
                    null
                }
            }
        }
    }

    private fun urlForLog(urlStr: String): String =
        urlStr.replace(Regex("access_token=[^&]+"), "access_token=***").take(120)

    private fun executeRequest(urlStr: String, straightDist: Double): RouteResult {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val code = conn.responseCode
            if (code != 200) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                } catch (_: Exception) {
                    null
                }
                Log.w(TAG, "HTTP $code${errBody?.let { ": $it" } ?: ""}")
                Log.w(TAG, "  URL: ${urlForLog(urlStr)}")
                conn.disconnect()
                return fallback(straightDist)
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val obj = JSONObject(body)
            val resultCode = obj.optString("code", "")
            if (resultCode.isNotEmpty() && resultCode != "Ok") {
                Log.w(TAG, "Directions code=$resultCode")
                Log.w(TAG, "  URL: ${urlForLog(urlStr)}")
                return fallback(straightDist)
            }

            val routes = obj.optJSONArray("routes") ?: return fallback(straightDist)
            if (routes.length() == 0) return fallback(straightDist)

            val route = routes.getJSONObject(0)
            val roadDist = route.optDouble("distance", straightDist * 1.4)
            val geometry = route.optJSONObject("geometry")
            val coords = geometry?.optJSONArray("coordinates")
            val points = mutableListOf<LatLon>()
            if (coords != null) {
                for (i in 0 until coords.length()) {
                    val c = coords.getJSONArray(i)
                    points.add(LatLon(c.getDouble(1), c.getDouble(0)))
                }
            }

            val ratio = if (straightDist > 0.0) roadDist / straightDist else 0.0
            Log.d(
                TAG,
                "경로 OK: 직선${straightDist.toInt()}m → 도로${roadDist.toInt()}m (${points.size}pt, ${"%.2f".format(ratio)}x)"
            )
            RouteResult(roadDist, straightDist, points, success = true)
        } catch (e: Exception) {
            Log.w(TAG, "요청 실패: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "  URL: ${urlForLog(urlStr)}")
            fallback(straightDist)
        }
    }

    private fun fallback(straightDist: Double): RouteResult {
        Log.w(TAG, "fallback 직선×1.4 (한국 도로 평균에 가깝게)")
        return RouteResult(straightDist * 1.4, straightDist, emptyList(), success = false)
    }

    fun remainingDistance(currentLat: Double, currentLon: Double, routePoints: List<LatLon>): Double {
        if (routePoints.isEmpty()) return -1.0
        var minDist = Double.MAX_VALUE
        var closestIdx = 0
        for (i in routePoints.indices) {
            val d = fastDist(currentLat, currentLon, routePoints[i].lat, routePoints[i].lon)
            if (d < minDist) {
                minDist = d
                closestIdx = i
            }
        }
        var totalDist = minDist
        for (i in closestIdx until routePoints.size - 1) {
            totalDist += fastDist(
                routePoints[i].lat,
                routePoints[i].lon,
                routePoints[i + 1].lat,
                routePoints[i + 1].lon
            )
        }
        return totalDist
    }

    fun isOnRoute(lat: Double, lon: Double, routePoints: List<LatLon>): Boolean {
        if (routePoints.isEmpty()) return true
        return routePoints.any { fastDist(lat, lon, it.lat, it.lon) < 200.0 }
    }

    fun isRouteAlignedWithBearing(
        routePoints: List<LatLon>,
        currentBearing: Double,
        checkDistanceM: Double = 200.0
    ): Boolean {
        if (routePoints.size < 3) return true
        var accumulated = 0.0
        var prevLat = routePoints[0].lat
        var prevLon = routePoints[0].lon
        for (i in 1 until routePoints.size) {
            val p = routePoints[i]
            accumulated += fastDist(prevLat, prevLon, p.lat, p.lon)
            if (accumulated > checkDistanceM) {
                val routeBearing = bearing(routePoints[0].lat, routePoints[0].lon, p.lat, p.lon)
                return angleDiff(currentBearing, routeBearing) < 60.0
            }
            prevLat = p.lat
            prevLon = p.lon
        }
        val last = routePoints.last()
        val routeBearing = bearing(routePoints[0].lat, routePoints[0].lon, last.lat, last.lon)
        return angleDiff(currentBearing, routeBearing) < 60.0
    }

    private fun fastDist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111320.0
        val dLon = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(la2)
        val x = kotlin.math.cos(la1) * kotlin.math.sin(la2) -
            kotlin.math.sin(la1) * kotlin.math.cos(la2) * kotlin.math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
