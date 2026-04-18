package com.teslcan.app

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class AlertInfo(
    val phase: Int,
    val distance: Int,
    val speedLimit: Int,
    val safetyCode: SafetyCode,
    val overspeed: Boolean,
    val camLat: Double,
    val camLon: Double,
    val isSection: Boolean = false,
    val sectionAvgSpeed: Int = 0,
    /** 레거지 CamAlert: 1000/500/300/100 구간 중 이번 틱에 새로 진입한 구간(음성 1회). 0이면 없음 */
    val zoneTriggered: Int = 0,
    val rawDistance: Int = distance,
    val d1: Int = 600,
    val d2: Int = 100
) {
    val camType: Int get() = safetyCode.toLegacyCamType()
}

/**
 * CamAlert 루트(`app/`)와 동일한 패턴: Mapbox 도로거리 비율 + 베어링 + 구간(zone) 1회 안내.
 */
class CameraEngine(
    private val cameraDb: CameraDatabase,
    private val settings: SettingsStore
) {

    companion object {
        private const val TAG = "CameraEngine"

        private const val SCAN_RADIUS = 1500
        private const val ALERT_DISTANCE = 600
        private const val LOST_DISTANCE = 800
        private const val COOLDOWN_MS = 60_000L
        private const val PASS_DISTANCE = 50.0

        private const val AHEAD_ANGLE = 40.0
        /** 차량→카메라 방위가 진행 방향과 이 각도(°) 이내면 전방(도시부 측면 카메라 억제) */
        private const val FORWARD_TO_CAM_DEG = 40.0

        /** 라우팅 API 실패 시 직선 fallback — 매우 보수적(평행도로 오탐 방지) */
        private const val FALLBACK_MAX_STRAIGHT_M = 300.0
        private const val FALLBACK_MAX_ANGLE_DEG = 25.0
        private const val FALLBACK_APPROACH_TICKS = 3
        private const val FALLBACK_PENDING_DIVERGE_M = 20.0
        private const val BEHIND_ANGLE = 110.0
        private const val MIN_SPEED_FOR_BEARING = 5
        private const val MIN_MOVE_FOR_BEARING = 5.0
        private const val TRACKING_GRACE_MS = 8000L

        /** 최대 알림 반경(ALERT_DISTANCE)과 맞춤. UI의 1km 스위치는 600m 구간에 매핑 */
        private val ALERT_ZONES = intArrayOf(600, 500, 300, 100)
        private const val BEARING_HISTORY_SIZE = 5

        /** 제한속도가 0이면 후보에서 제외하는 타입(CSV 누락 시). 신호·구간·어린이 등은 0이어도 유지 */
        private val SPEED_LIMIT_REQUIRED_IF_ZERO = setOf(
            SafetyCode.FIXED_SPEED,
            SafetyCode.MOVABLE_SPEED,
            SafetyCode.BOXED_SPEED,
            SafetyCode.BACKWARD_SPEED
        )

        private fun camTypePriority(legacyType: Int): Int = when (legacyType) {
            6 -> 0
            3 -> 1
            0, 1 -> 2
            4 -> 3
            2 -> 4
            5 -> 5
            else -> 3
        }

        fun idle(): AlertInfo =
            AlertInfo(0, 0, 0, SafetyCode.UNKNOWN, false, 0.0, 0.0)
    }

    private val router = MapboxRouter()
    private var cachedRoute: MapboxRouter.RouteResult? = null
    private var routeRequestInProgress = false

    private var trackingCamera: CameraRecord? = null
    private var minDistReached = Double.MAX_VALUE
    private var lastDist = Double.MAX_VALUE
    private var approachCount = 0
    private var recedeCount = 0
    private var trackingStartMs = 0L
    private val zoneFired = mutableSetOf<Int>()

    private var prevLat = 0.0
    private var prevLon = 0.0
    private var currentBearing = -1.0
    private var bearingValid = false
    private val bearingHistory = mutableListOf<Double>()

    private data class PassedCamera(val lat: Double, val lon: Double, val timeMs: Long)
    private val passedCameras = mutableListOf<PassedCamera>()

    private var sectionEntry: CameraRecord? = null
    private var sectionEntryTime = 0L
    private var sectionDistance = 0.0

    /** heading 미상 + API 실패 시: 직선·각도 조건 통과 후 N틱 연속 접근 시에만 추적 시작 */
    private var pendingFallbackCam: CameraRecord? = null
    private var pendingFallbackLastDist = Double.MAX_VALUE
    private var pendingApproachTicks = 0

    /** 시뮬 등에서만 사용. null이면 내부 베어링(이동 벡터) 사용. */
    var bearingOverrideDeg: Float? = null

    @Deprecated("레거지 엔진은 내부 베어링만 사용. 호출해도 무시됩니다.")
    fun updateBearing(@Suppress("UNUSED_PARAMETER") gpsDeg: Float) {
    }

    fun reset() {
        resetTracking()
        prevLat = 0.0
        prevLon = 0.0
        currentBearing = -1.0
        bearingValid = false
        bearingHistory.clear()
        passedCameras.clear()
        bearingOverrideDeg = null
        sectionEntry = null
        sectionEntryTime = 0L
        sectionDistance = 0.0
        clearPendingFallback()
    }

    private fun resetTracking() {
        trackingCamera = null
        cachedRoute = null
        routeRequestInProgress = false
        minDistReached = Double.MAX_VALUE
        lastDist = Double.MAX_VALUE
        approachCount = 0
        recedeCount = 0
        trackingStartMs = 0L
        zoneFired.clear()
        clearPendingFallback()
    }

    private fun clearPendingFallback() {
        pendingFallbackCam = null
        pendingFallbackLastDist = Double.MAX_VALUE
        pendingApproachTicks = 0
    }

    private fun applyTrackingStart(cam: CameraRecord, route: MapboxRouter.RouteResult, straightM: Double) {
        trackingCamera = cam
        cachedRoute = route
        minDistReached = route.roadDistance
        lastDist = route.roadDistance
        approachCount = 0
        recedeCount = 0
        zoneFired.clear()
        trackingStartMs = System.currentTimeMillis()
        sectionEntry = null
        sectionEntryTime = 0L
        sectionDistance = 0.0

        // 추적 시작 시점에 이미 안쪽 zone에 들어와 있으면, 지나온 바깥 zone은 소진 처리.
        // 가장 바깥 zone 한 개만 buildAlert 첫 틱에서 zoneTriggered로 재생되도록 제외(나머지는 스킵).
        val startDist = route.roadDistance.toInt()
        var outermostAlreadyInside = 0
        for (zone in ALERT_ZONES) {
            if (startDist <= zone) {
                zoneFired.add(zone)
                if (outermostAlreadyInside == 0) outermostAlreadyInside = zone
            }
        }
        if (outermostAlreadyInside > 0) {
            zoneFired.remove(outermostAlreadyInside)
        }

        Log.d(
            TAG,
            "추적시작: ${straightM.toInt()}m→${route.roadDistance.toInt()}m " +
                "${cam.safetyCode.label} limit=${cam.speedLimit} heading=${cam.direction?.toInt() ?: -1}° " +
                "zonePre=${zoneFired.joinToString()} replayOuter=$outermostAlreadyInside"
        )
    }

    fun check(
        lat: Double,
        lon: Double,
        speedKmh: Int,
        overThreshold: Int,
        bearingOverrideDeg: Double? = null
    ): AlertInfo? = update(lat, lon, speedKmh, overThreshold, bearingOverrideDeg)

    private fun update(
        lat: Double,
        lon: Double,
        speedKmh: Int,
        overThreshold: Int,
        bearingOverrideDeg: Double?
    ): AlertInfo? {
        updateBearingInternal(lat, lon, speedKmh)
        if (bearingOverrideDeg != null && bearingOverrideDeg >= 0.0) {
            currentBearing = (bearingOverrideDeg + 360.0) % 360.0
            bearingValid = true
            bearingHistory.clear()
            bearingHistory.add(currentBearing)
        }

        val now = System.currentTimeMillis()
        passedCameras.removeAll { now - it.timeMs > COOLDOWN_MS }

        if (trackingCamera != null) {
            return updateTracking(lat, lon, speedKmh, overThreshold, now)
        }
        if (pendingFallbackCam != null) {
            if (tickPendingFallback(lat, lon, now)) {
                return updateTracking(lat, lon, speedKmh, overThreshold, now)
            }
            if (pendingFallbackCam != null) return null
        }
        if (routeRequestInProgress) return null
        return findAheadCamera(lat, lon, speedKmh, overThreshold)
    }

    /**
     * @return true 이면 이번 틱에 추적이 시작됐으므로 호출측에서 updateTracking을 이어서 호출.
     */
    private fun tickPendingFallback(lat: Double, lon: Double, @Suppress("UNUSED_PARAMETER") now: Long): Boolean {
        val cam = pendingFallbackCam ?: return false
        if (!bearingValid) {
            Log.d(TAG, "fallback pending 취소: bearing 미확인")
            clearPendingFallback()
            return false
        }
        val d = distanceBetween(lat, lon, cam.lat, cam.lon)
        val diffToCam = angleDiff(currentBearing, bearingBetween(lat, lon, cam.lat, cam.lon))
        if (d > FALLBACK_MAX_STRAIGHT_M + 80.0 || diffToCam > FALLBACK_MAX_ANGLE_DEG + 15.0) {
            Log.d(TAG, "fallback pending 취소: dist=${d.toInt()}m angle=${"%.0f".format(Locale.US, diffToCam)}°")
            clearPendingFallback()
            return false
        }
        if (d > pendingFallbackLastDist + FALLBACK_PENDING_DIVERGE_M) {
            Log.d(TAG, "fallback pending 취소: 이탈(+${FALLBACK_PENDING_DIVERGE_M.toInt()}m) dist=${d.toInt()}m")
            clearPendingFallback()
            return false
        }
        if (d < pendingFallbackLastDist - 3.0) {
            pendingApproachTicks++
            pendingFallbackLastDist = d
            Log.d(
                TAG,
                "  fallback pending 접근 ${pendingApproachTicks}/$FALLBACK_APPROACH_TICKS dist=${d.toInt()}m"
            )
            if (pendingApproachTicks >= FALLBACK_APPROACH_TICKS) {
                val fd = d * 1.3
                val route = MapboxRouter.RouteResult(
                    roadDistance = fd,
                    straightDistance = d,
                    routePoints = emptyList(),
                    success = false
                )
                clearPendingFallback()
                applyTrackingStart(cam, route, d)
                return true
            }
        }
        return false
    }

    private fun updateBearingInternal(lat: Double, lon: Double, speedKmh: Int) {
        if (prevLat != 0.0 && prevLon != 0.0 && speedKmh >= MIN_SPEED_FOR_BEARING) {
            val moved = distanceBetween(prevLat, prevLon, lat, lon)
            if (moved > MIN_MOVE_FOR_BEARING) {
                val newBearing = bearingBetween(prevLat, prevLon, lat, lon)
                bearingHistory.add(newBearing)
                if (bearingHistory.size > BEARING_HISTORY_SIZE) bearingHistory.removeAt(0)
                var sinSum = 0.0
                var cosSum = 0.0
                for (b in bearingHistory) {
                    sinSum += sin(Math.toRadians(b))
                    cosSum += cos(Math.toRadians(b))
                }
                currentBearing = (Math.toDegrees(atan2(sinSum, cosSum)) + 360.0) % 360.0
                bearingValid = true
            }
        } else if (speedKmh < MIN_SPEED_FOR_BEARING) {
            bearingHistory.clear()
            bearingValid = false
        }
        prevLat = lat
        prevLon = lon
    }

    private data class Candidate(val cam: CameraRecord, val dist: Double, val angleDiff: Double)

    private fun findAheadCamera(lat: Double, lon: Double, speedKmh: Int, overThreshold: Int): AlertInfo? {
        val allCameras = cameraDb.findNearbyCameras(
            lat, lon,
            headingDeg = null,
            maxDistanceMeters = SCAN_RADIUS.toDouble(),
            aheadAngle = 360f
        )
        if (allCameras.isEmpty()) return null
        if (routeRequestInProgress) return null

        val candidates = mutableListOf<Candidate>()
        for (cam in allCameras) {
            if (!isAlertTarget(cam.safetyCode, cam.speedLimit)) continue
            if (!DrivingProfile.isTypeEnabled(cam.safetyCode.toLegacyCamType(), settings)) continue
            if (cam.speedLimit <= 0 && cam.safetyCode in SPEED_LIMIT_REQUIRED_IF_ZERO) continue
            if (isInCooldown(cam)) continue

            val dist = distanceBetween(lat, lon, cam.lat, cam.lon)
            if (dist > ALERT_DISTANCE) continue

            val bearingToCam = bearingBetween(lat, lon, cam.lat, cam.lon)
            val camHeading = cam.direction?.toInt() ?: -1

            if (bearingValid) {
                val diffToCam = angleDiff(currentBearing, bearingToCam)
                if (diffToCam > FORWARD_TO_CAM_DEG) continue

                if (camHeading >= 0) {
                    val headingDiff = angleDiff(currentBearing, camHeading.toDouble())
                    if (headingDiff > 90.0) {
                        Log.d(
                            TAG,
                            "  방향필터: ${cam.safetyCode.label} heading=${camHeading}° " +
                                "내bearing=${"%.0f".format(Locale.US, currentBearing)}° " +
                                "Δ=${"%.0f".format(Locale.US, headingDiff)}° → 반대방향 스킵"
                        )
                        continue
                    }
                } else {
                    if (dist > 200.0 && diffToCam > AHEAD_ANGLE) continue
                }
            } else {
                if (camHeading < 0 && dist > 200.0) continue
            }

            val angleDiffVal = if (bearingValid) angleDiff(currentBearing, bearingToCam) else 0.0
            candidates.add(Candidate(cam, dist, angleDiffVal))
        }

        if (bearingValid) {
            Log.d(TAG, "검색: bearing=${"%.0f".format(Locale.US, currentBearing)}° raw=${allCameras.size}→후보 ${candidates.size}개")
        } else {
            Log.d(TAG, "검색: bearing=미확인 raw=${allCameras.size}→후보 ${candidates.size}개")
        }

        if (candidates.isEmpty() && allCameras.isNotEmpty()) {
            val sample = allCameras.first()
            val d0 = distanceBetween(lat, lon, sample.lat, sample.lon)
            val bearToSample = bearingBetween(lat, lon, sample.lat, sample.lon)
            val sampleHead = sample.direction?.toInt() ?: -1
            Log.d(
                TAG,
                "  예시 탈락: ${sample.safetyCode.label} limit=${sample.speedLimit} " +
                    "dist=${"%.0f".format(Locale.US, d0)}m heading=${sampleHead}° " +
                    "내bearing=${"%.0f".format(Locale.US, currentBearing)}° " +
                    "카메라방위=${"%.0f".format(Locale.US, bearToSample)}° " +
                    "alert=${isAlertTarget(sample.safetyCode, sample.speedLimit)} " +
                    "typeOn=${DrivingProfile.isTypeEnabled(sample.safetyCode.toLegacyCamType(), settings)}"
            )
        }

        if (candidates.isEmpty()) return null

        val sorted = candidates.sortedWith(
            compareBy<Candidate> { it.dist }
                .thenBy { camTypePriority(it.cam.safetyCode.toLegacyCamType()) }
                .thenBy { it.angleDiff }
        )
        val topCandidates = sorted.take(3)

        routeRequestInProgress = true
        val curLat = lat
        val curLon = lon
        val curSpeed = speedKmh
        val curBearing = currentBearing
        val curBearingValid = bearingValid

        Thread {
            var bestCam: CameraRecord? = null
            var bestRoute: MapboxRouter.RouteResult? = null
            var bestStraight = 0.0
            var pendingUnknownHeading: Candidate? = null

            for (candidate in topCandidates) {
                val route = router.getRoute(curLat, curLon, candidate.cam.lat, candidate.cam.lon, candidate.dist)
                if (route.success) {
                    val ratio = if (candidate.dist > 0.0) route.roadDistance / candidate.dist else 0.0
                    val maxRatio = when {
                        curSpeed >= 80 -> 2.0
                        curSpeed >= 40 -> 2.5
                        else -> 3.0
                    }
                    Log.d(
                        TAG,
                        "검증: ${candidate.cam.safetyCode.label} 직선${candidate.dist.toInt()}m 도로${route.roadDistance.toInt()}m " +
                            "${"%.1f".format(Locale.US, ratio)}x (기준${"%.1f".format(Locale.US, maxRatio)}x)"
                    )

                    if (ratio > maxRatio) {
                        Log.d(TAG, "  → 비율 초과 스킵")
                        continue
                    }
                    if (route.roadDistance > ALERT_DISTANCE * 1.5) {
                        Log.d(TAG, "  → 도로거리 ${route.roadDistance.toInt()}m 스킵")
                        continue
                    }
                    if (curBearingValid && route.routePoints.isNotEmpty()) {
                        val aligned = router.isRouteAlignedWithBearing(route.routePoints, curBearing, 200.0)
                        if (!aligned) {
                            Log.d(TAG, "  → 방향 불일치 스킵")
                            continue
                        }
                    }
                    if (curBearingValid && route.routePoints.size >= 2) {
                        val pts = route.routePoints
                        val p0 = pts[pts.size - 2]
                        val p1 = pts[pts.size - 1]
                        val approachBearing = bearingBetween(p0.lat, p0.lon, p1.lat, p1.lon)
                        val approachDiff = angleDiff(curBearing, approachBearing)
                        if (approachDiff > 90.0) {
                            Log.d(
                                TAG,
                                "  → 도착방향 불일치: 접근=${"%.0f".format(Locale.US, approachBearing)}° " +
                                    "내bearing=${"%.0f".format(Locale.US, curBearing)}° " +
                                    "Δ=${"%.0f".format(Locale.US, approachDiff)}° 스킵"
                            )
                            continue
                        }
                    }
                    bestCam = candidate.cam
                    bestRoute = route
                    bestStraight = candidate.dist
                    Log.d(TAG, "  ✓ 확정! ${route.roadDistance.toInt()}m")
                    break
                } else {
                    val fd = candidate.dist * 1.3
                    Log.d(TAG, "  API 실패 → 직선 fallback ${candidate.dist.toInt()}m (도로산 ${fd.toInt()}m)")
                    val allowFallback = candidate.dist <= FALLBACK_MAX_STRAIGHT_M &&
                        curBearingValid &&
                        candidate.angleDiff <= FALLBACK_MAX_ANGLE_DEG
                    if (!allowFallback) {
                        Log.d(
                            TAG,
                            "  → fallback 기각 dist=${candidate.dist.toInt()}m(기준${
                                FALLBACK_MAX_STRAIGHT_M.toInt()
                            }m) angle=${"%.0f".format(Locale.US, candidate.angleDiff)}°(기준${
                                FALLBACK_MAX_ANGLE_DEG.toInt()
                            }°) bearingValid=$curBearingValid"
                        )
                        continue
                    }
                    val camHeading = candidate.cam.direction?.toInt() ?: -1
                    if (camHeading >= 0) {
                        bestCam = candidate.cam
                        bestRoute = MapboxRouter.RouteResult(
                            roadDistance = fd,
                            straightDistance = candidate.dist,
                            routePoints = emptyList(),
                            success = false
                        )
                        bestStraight = candidate.dist
                        Log.d(
                            TAG,
                            "  → fallback 확정! ${fd.toInt()}m (각도 ${"%.0f".format(Locale.US, candidate.angleDiff)}° heading≥0)"
                        )
                        break
                    }
                    pendingUnknownHeading = candidate
                    Log.d(
                        TAG,
                        "  → fallback 등록(heading 미상): 접근 ${FALLBACK_APPROACH_TICKS}틱 확인 후 확정"
                    )
                    break
                }
            }

            Handler(Looper.getMainLooper()).post {
                routeRequestInProgress = false
                val pickedCam = bestCam
                val pickedRoute = bestRoute
                if (pickedCam != null && pickedRoute != null) {
                    applyTrackingStart(pickedCam, pickedRoute, bestStraight)
                } else if (pendingUnknownHeading != null) {
                    val c = pendingUnknownHeading!!
                    pendingFallbackCam = c.cam
                    pendingFallbackLastDist = c.dist
                    pendingApproachTicks = 0
                    Log.d(
                        TAG,
                        "fallback pending 등록 0/$FALLBACK_APPROACH_TICKS 직선=${c.dist.toInt()}m " +
                            "${c.cam.safetyCode.label}"
                    )
                } else {
                    Log.d(TAG, "적합 카메라 없음 (${topCandidates.size}개 검토)")
                }
            }
        }.start()

        return null
    }

    private fun updateTracking(
        lat: Double,
        lon: Double,
        speedKmh: Int,
        overThreshold: Int,
        now: Long
    ): AlertInfo? {
        val cam = trackingCamera ?: return null
        val straightDist = distanceBetween(lat, lon, cam.lat, cam.lon)
        val route = cachedRoute
        val roadDist = if (route != null && route.routePoints.isNotEmpty()) {
            val remaining = router.remainingDistance(lat, lon, route.routePoints)
            if (remaining > 0.0) remaining else straightDist * 1.3
        } else {
            straightDist * 1.3
        }

        if (cam.safetyCode == SafetyCode.SECTION_IN && sectionEntry == null && roadDist < 100.0) {
            sectionEntry = cam
            sectionEntryTime = now
            sectionDistance = 0.0
            Log.d(TAG, "SECTION_IN limit=${cam.speedLimit}")
        }
        if (sectionEntry != null) {
            sectionDistance += speedKmh / 3.6
        }

        if (roadDist < lastDist - 5.0) {
            approachCount++
            recedeCount = 0
        } else if (roadDist > lastDist + 5.0) {
            recedeCount++
            approachCount = 0
        }

        if (roadDist < minDistReached) minDistReached = roadDist
        lastDist = roadDist
        val inGrace = (now - trackingStartMs) < TRACKING_GRACE_MS

        if (!inGrace && minDistReached < PASS_DISTANCE && recedeCount >= 2 && roadDist > minDistReached + 10.0) {
            Log.d(TAG, "[CAM] 빠른통과")
            return onCameraPassed(cam, now, speedKmh, overThreshold)
        }
        if (straightDist < 30.0 && recedeCount >= 1) {
            Log.d(TAG, "[CAM] 근접통과")
            return onCameraPassed(cam, now, speedKmh, overThreshold)
        }

        if (bearingValid && minDistReached < 300.0) {
            val bearingToCam = bearingBetween(lat, lon, cam.lat, cam.lon)
            val diff = angleDiff(currentBearing, bearingToCam)
            if (diff > BEHIND_ANGLE && straightDist > 30.0) {
                Log.d(TAG, "[CAM] 통과(heading)")
                return onCameraPassed(cam, now, speedKmh, overThreshold)
            }
        }

        if (!inGrace && minDistReached < 100.0 && recedeCount >= 3 && roadDist > minDistReached + 40.0) {
            Log.d(TAG, "[CAM] 통과(recede)")
            return onCameraPassed(cam, now, speedKmh, overThreshold)
        }
        if (!inGrace && minDistReached < PASS_DISTANCE && roadDist > minDistReached + 20.0) {
            Log.d(TAG, "[CAM] 통과(close)")
            return onCameraPassed(cam, now, speedKmh, overThreshold)
        }

        if (!inGrace && route != null && route.routePoints.isNotEmpty() &&
            !router.isOnRoute(lat, lon, route.routePoints)
        ) {
            Log.d(TAG, "[CAM] 경로이탈 해제")
            resetTracking()
            return idle()
        }

        if (!inGrace && approachCount == 0 && recedeCount >= 8) {
            Log.d(TAG, "[CAM] 해제(미접근)")
            resetTracking()
            return idle()
        }
        if (!inGrace && bearingValid && recedeCount >= 5) {
            val bearingToCam = bearingBetween(lat, lon, cam.lat, cam.lon)
            val diff = angleDiff(currentBearing, bearingToCam)
            if (diff > 90.0) {
                Log.d(TAG, "[CAM] 해제(뒤)")
                resetTracking()
                return idle()
            }
        }
        if (straightDist > LOST_DISTANCE) {
            Log.d(TAG, "[CAM] 해제(거리초과)")
            resetTracking()
            return idle()
        }

        return buildAlert(roadDist, straightDist.toInt(), cam, speedKmh, overThreshold, now)
    }

    private fun onCameraPassed(cam: CameraRecord, now: Long, speedKmh: Int, overThreshold: Int): AlertInfo {
        passedCameras.add(PassedCamera(cam.lat, cam.lon, now))

        if (cam.safetyCode == SafetyCode.SECTION_OUT && sectionEntry != null) {
            val elapsed = (now - sectionEntryTime) / 1000.0
            val avgSpeed = if (elapsed > 0) (sectionDistance / elapsed * 3.6).toInt() else 0
            val result = AlertInfo(
                phase = 4,
                distance = 0,
                speedLimit = cam.speedLimit,
                safetyCode = cam.safetyCode,
                overspeed = avgSpeed > cam.speedLimit + overThreshold,
                camLat = cam.lat,
                camLon = cam.lon,
                isSection = true,
                sectionAvgSpeed = avgSpeed,
                zoneTriggered = 0,
                rawDistance = 0
            )
            resetTracking()
            return result
        }

        val result = AlertInfo(
            phase = 0,
            distance = 0,
            speedLimit = cam.speedLimit,
            safetyCode = cam.safetyCode,
            overspeed = false,
            camLat = cam.lat,
            camLon = cam.lon,
            zoneTriggered = 0,
            rawDistance = 0
        )
        resetTracking()
        return result
    }

    private fun buildAlert(
        roadDist: Double,
        rawDist: Int,
        cam: CameraRecord,
        speedKmh: Int,
        overThreshold: Int,
        @Suppress("UNUSED_PARAMETER") now: Long
    ): AlertInfo {
        val roadDistInt = roadDist.toInt()
        val roundedDist = ((roadDistInt + 50) / 100) * 100
        val phase = when {
            roadDistInt <= 100 -> 4
            roadDistInt <= 300 -> 3
            roadDistInt <= 500 -> 2
            roadDistInt <= ALERT_DISTANCE -> 1
            else -> 0
        }
        val onViolation = cam.speedLimit > 0 && speedKmh > cam.speedLimit + overThreshold
        var zoneTriggered = 0
        for (zone in ALERT_ZONES) {
            if (roadDistInt <= zone && zone !in zoneFired) {
                zoneFired.add(zone)
                zoneTriggered = zone
                break
            }
        }
        val sectionAvg = if (sectionEntry != null && sectionEntryTime > 0L) {
            val elapsed = (System.currentTimeMillis() - sectionEntryTime) / 1000.0
            if (elapsed > 0) (sectionDistance / elapsed * 3.6).toInt() else 0
        } else {
            0
        }
        return AlertInfo(
            phase = phase,
            distance = roundedDist,
            speedLimit = cam.speedLimit,
            safetyCode = cam.safetyCode,
            overspeed = onViolation,
            camLat = cam.lat,
            camLon = cam.lon,
            isSection = sectionEntry != null,
            sectionAvgSpeed = sectionAvg,
            zoneTriggered = zoneTriggered,
            rawDistance = rawDist.coerceAtLeast(0),
            d1 = ALERT_DISTANCE
        )
    }

    private fun isInCooldown(cam: CameraRecord): Boolean =
        passedCameras.any { distanceBetween(it.lat, it.lon, cam.lat, cam.lon) < 50.0 }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
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
        val diff = abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }
}

/** 과속·구간·보호구역·신호위반(단속) 등 알림 대상. 레거시 CSV type=4 → SIGNAL. */
private fun isAlertTarget(code: SafetyCode, speedLimit: Int): Boolean = when (code) {
    SafetyCode.FIXED_SPEED,
    SafetyCode.MOVABLE_SPEED,
    SafetyCode.SIGNAL_AND_SPEED,
    SafetyCode.SIGNAL,
    SafetyCode.SECTION_IN,
    SafetyCode.SECTION_OUT,
    SafetyCode.SECTION_ZONE,
    SafetyCode.BACKWARD_SPEED,
    SafetyCode.BACKWARD_SIGNAL_SPEED,
    SafetyCode.LANE_AND_SPEED,
    SafetyCode.BOXED_SPEED,
    SafetyCode.BUSLANE_AND_SPEED -> true
    SafetyCode.CHILDREN_ZONE -> speedLimit > 0
    else -> false
}
