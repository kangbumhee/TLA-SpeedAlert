package com.teslcan.app

import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Overpass API로 카메라 주변 OSM way를 조회해, OSRM nearest만으로는 구분이 어려운
 * 반대차선·교차로 단속 여부를 보조 판정합니다. (HttpURLConnection, OkHttp 미사용)
 */
object OverpassService {

    private const val TAG = "OverpassService"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val TIMEOUT_MS = 12_000
    private const val USER_AGENT = "TLA-SpeedAlert/1.0 (camera filter)"

    data class WaySegment(
        val wayId: Long,
        val name: String?,
        val isOneway: Boolean,
        val onewayReverse: Boolean,
        val bearing: Double,
        val lanes: Int?,
        val highway: String,
        /** 카메라 좌표에서 이 way geometry(세그먼트 중점)까지 최소 거리(m) */
        val minDistToCamM: Double
    )

    private val wayCache = LruCache<String, List<WaySegment>>(200)

    private fun cacheKey(lat: Double, lon: Double, radiusM: Int): String =
        "${(lat * 1000).roundToInt()}_${(lon * 1000).roundToInt()}_$radiusM"

    private fun fastDistM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111320.0
        val dLon = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun bearingBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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

    /**
     * 카메라 주변 OSM way 목록. 네트워크/파싱 실패 시 빈 리스트.
     */
    fun queryWaysNearCameraBlocking(
        lat: Double,
        lon: Double,
        radiusM: Int = 35
    ): List<WaySegment> {
        val key = cacheKey(lat, lon, radiusM)
        wayCache.get(key)?.let { return it }

        val query = """
            [out:json][timeout:8];
            way(around:$radiusM,$lat,$lon)
              ["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified)$"];
            out body geom;
        """.trimIndent()

        val list = try {
            val urlStr = "$OVERPASS_URL?data=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) {
                Log.w(TAG, "Overpass HTTP ${conn.responseCode}")
                conn.disconnect()
                emptyList()
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                parseWaysFromOverpass(lat, lon, body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overpass 요청 실패: ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }

        if (list.isNotEmpty()) {
            wayCache.put(key, list)
        }
        return list
    }

    private fun parseWaysFromOverpass(camLat: Double, camLon: Double, jsonBody: String): List<WaySegment> {
        val results = mutableListOf<WaySegment>()
        val root = JSONObject(jsonBody)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") != "way") continue
            val wayId = el.optLong("id", 0L)
            if (wayId == 0L) continue
            val tags = el.optJSONObject("tags") ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue

            var minDist = Double.MAX_VALUE
            var segBearing = 0.0
            for (j in 0 until geom.length() - 1) {
                val n1 = geom.getJSONObject(j)
                val n2 = geom.getJSONObject(j + 1)
                val la1 = n1.getDouble("lat")
                val lo1 = n1.getDouble("lon")
                val la2 = n2.getDouble("lat")
                val lo2 = n2.getDouble("lon")
                val midLat = (la1 + la2) / 2.0
                val midLon = (lo1 + lo2) / 2.0
                val dist = fastDistM(camLat, camLon, midLat, midLon)
                if (dist < minDist) {
                    minDist = dist
                    segBearing = bearingBetween(la1, lo1, la2, lo2)
                }
            }
            if (minDist == Double.MAX_VALUE) continue

            val onewayTag = tags.optString("oneway", "no").lowercase()
            val isOneway = onewayTag in listOf("yes", "1", "true", "-1")
            val onewayReverse = onewayTag == "-1"
            val nameRaw = tags.optString("name", "").trim()
            val name = nameRaw.ifEmpty { null }
            val lanes = tags.optString("lanes", "").trim().toIntOrNull()
            val highway = tags.optString("highway", "")

            results.add(
                WaySegment(
                    wayId = wayId,
                    name = name,
                    isOneway = isOneway,
                    onewayReverse = onewayReverse,
                    bearing = segBearing,
                    lanes = lanes,
                    highway = highway,
                    minDistToCamM = minDist
                )
            )
        }
        return results
    }

    /**
     * true면 이 카메라는 우리 진행 방향과 무관하거나 반대 차선·역주행 단속으로 보고 후보에서 제외.
     * [ways]는 [queryWaysNearCameraBlocking] 결과(빈 리스트면 false).
     */
    fun isOppositeLaneFromWays(
        vehicleBearingDeg: Double,
        camHeading: Int,
        ways: List<WaySegment>
    ): Boolean {
        if (ways.isEmpty()) return false

        if (camHeading >= 0) {
            val diff = angleDiff(camHeading.toDouble(), vehicleBearingDeg)
            if (diff > 120.0) {
                Log.d(TAG, "Overpass: 카메라 heading vs 차량 bearing Δ=${diff.toInt()}° > 120°")
                return true
            }
        }

        val vehicleWay = ways.minByOrNull { w ->
            minOf(
                angleDiff(vehicleBearingDeg, w.bearing),
                angleDiff(vehicleBearingDeg, (w.bearing + 180.0) % 360.0)
            )
        } ?: return false

        if (vehicleWay.isOneway) {
            val wayDir = if (vehicleWay.onewayReverse) {
                (vehicleWay.bearing + 180.0) % 360.0
            } else {
                vehicleWay.bearing
            }
            if (angleDiff(vehicleBearingDeg, wayDir) > 90.0) {
                Log.d(TAG, "Overpass: 일방통행 역주행 의심 (wayDir vs bearing)")
                return true
            }
        }

        if (ways.size >= 2) {
            val vb = vehicleBearingDeg
            val aligned = ways.minByOrNull { w ->
                minOf(angleDiff(vb, w.bearing), angleDiff(vb, (w.bearing + 180.0) % 360.0))
            }
            val rev = (vb + 180.0) % 360.0
            val opposed = ways.minByOrNull { w ->
                minOf(angleDiff(rev, w.bearing), angleDiff(rev, (w.bearing + 180.0) % 360.0))
            }
            if (aligned != null && opposed != null && aligned.wayId != opposed.wayId) {
                val da = aligned.minDistToCamM
                val dp = opposed.minDistToCamM
                if (dp + 5.0 < da) {
                    Log.d(TAG, "Overpass: 반대편 way에 카메라 더 근접 (aligned=${da.toInt()}m opposed=${dp.toInt()}m)")
                    return true
                }
            }
        }

        return false
    }
}
