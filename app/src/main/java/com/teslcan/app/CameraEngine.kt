package com.teslcan.app

import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
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
    val sectionAvgSpeed: Int = 0
) {
    /** UI·로그용 레거시 타입 */
    val camType: Int get() = safetyCode.toLegacyCamType()
}

/**
 * 카카오내비 안전운전(거리·우선순위) + SpeedAlert(단계·heading 스캔) 패턴을 단순화해 구현.
 */
class CameraEngine(
    private val cameraDb: CameraDatabase,
    private val settings: SettingsStore
) {

    companion object {
        private const val TAG = "CameraEngine"

        const val SCAN_RADIUS = 1500.0
        private const val ALERT_PHASE1 = 500.0
        private const val ALERT_PHASE2 = 300.0
        private const val ALERT_PHASE3 = 100.0
        private const val LOST_DISTANCE = 50.0
        private const val COOLDOWN_MS = 60_000L
        private const val AHEAD_ANGLE = 40f

        fun idle(): AlertInfo =
            AlertInfo(0, 0, 0, SafetyCode.UNKNOWN, false, 0.0, 0.0)
    }

    private var trackingCamera: CameraRecord? = null
    private var lastEnginePhase = 0
    private var lastDistance = 0.0
    private var haveDistanceSample = false
    private val passedCooldown = mutableMapOf<Long, Long>()

    private var sectionEntry: CameraRecord? = null
    private var sectionEntryTime = 0L
    private var sectionDistance = 0.0

    var bearingOverrideDeg: Float? = null
    private val bearingHistory = mutableListOf<Float>()

    fun updateBearing(gpsDeg: Float) {
        bearingHistory.add(gpsDeg)
        if (bearingHistory.size > 5) bearingHistory.removeAt(0)
    }

    fun reset() {
        resetTracking()
        bearingHistory.clear()
        bearingOverrideDeg = null
    }

    private fun resetTracking() {
        trackingCamera = null
        lastEnginePhase = 0
        lastDistance = 0.0
        haveDistanceSample = false
        sectionEntry = null
        sectionEntryTime = 0L
        sectionDistance = 0.0
    }

    private fun effectiveHeading(): Float? =
        bearingOverrideDeg ?: bearingHistory.lastOrNull()

    /**
     * @param bearingOverrideDeg 시뮬 등에서 주행 방향(도). null이면 GPS bearingHistory 사용.
     */
    fun check(
        lat: Double,
        lon: Double,
        speedKmh: Int,
        overThreshold: Int,
        bearingOverrideDeg: Double? = null
    ): AlertInfo {
        if (bearingOverrideDeg != null && bearingOverrideDeg >= 0) {
            this.bearingOverrideDeg = bearingOverrideDeg.toFloat()
        }

        val now = System.currentTimeMillis()
        passedCooldown.entries.removeAll { now - it.value > COOLDOWN_MS }

        val heading = effectiveHeading()
        val raw = cameraDb.findNearbyCameras(
            lat,
            lon,
            heading,
            maxDistanceMeters = SCAN_RADIUS,
            aheadAngle = if (heading != null) AHEAD_ANGLE else 360f
        )
        val cameras = raw.filter {
            DrivingProfile.isTypeEnabled(it.safetyCode.toLegacyCamType(), settings)
        }.filter { it.id !in passedCooldown }

        if (cameras.isEmpty()) {
            resetTracking()
            return idle()
        }

        val target = cameras.first()
        val dist = haversine(lat, lon, target.lat, target.lon)

        if (trackingCamera != null && trackingCamera!!.id != target.id) {
            resetTracking()
        }
        trackingCamera = target

        if (haveDistanceSample && dist > lastDistance + LOST_DISTANCE) {
            Log.d(TAG, "PASS id=${target.id} ${target.safetyCode.label}")
            passedCooldown[target.id] = now
            val passLimit = target.speedLimit
            val passCode = target.safetyCode
            val passLat = target.lat
            val passLon = target.lon

            if (target.safetyCode == SafetyCode.SECTION_OUT && sectionEntry != null) {
                val elapsed = (now - sectionEntryTime) / 1000.0
                val avgSpeed = if (elapsed > 0) (sectionDistance / elapsed * 3.6).toInt() else 0
                resetTracking()
                return AlertInfo(
                    phase = 4,
                    distance = 0,
                    speedLimit = target.speedLimit,
                    safetyCode = target.safetyCode,
                    overspeed = avgSpeed > target.speedLimit + overThreshold,
                    camLat = target.lat,
                    camLon = target.lon,
                    isSection = true,
                    sectionAvgSpeed = avgSpeed
                )
            }

            resetTracking()
            return AlertInfo(0, 0, passLimit, passCode, false, passLat, passLon)
        }
        lastDistance = dist
        haveDistanceSample = true

        if (target.safetyCode == SafetyCode.SECTION_IN && sectionEntry == null && dist < ALERT_PHASE3) {
            sectionEntry = target
            sectionEntryTime = now
            sectionDistance = 0.0
            Log.d(TAG, "SECTION_IN limit=${target.speedLimit}")
        }
        if (sectionEntry != null) {
            sectionDistance += speedKmh / 3.6
        }

        val onViolation = target.speedLimit > 0 && speedKmh > target.speedLimit + overThreshold
        val phase = when {
            dist <= ALERT_PHASE3 -> 4
            dist <= ALERT_PHASE2 -> 3
            dist <= ALERT_PHASE1 -> 2
            dist <= SCAN_RADIUS -> 1
            else -> 0
        }

        if (phase > lastEnginePhase) {
            Log.d(TAG, "ALERT phase=$phase dist=${dist.toInt()}m ${target.safetyCode.label} limit=${target.speedLimit}")
        }
        lastEnginePhase = phase

        val sectionAvg = if (sectionEntry != null && sectionEntryTime > 0L) {
            val elapsed = (now - sectionEntryTime) / 1000.0
            if (elapsed > 0) (sectionDistance / elapsed * 3.6).toInt() else 0
        } else {
            0
        }

        return AlertInfo(
            phase = phase,
            distance = dist.toInt(),
            speedLimit = target.speedLimit,
            safetyCode = target.safetyCode,
            overspeed = onViolation,
            camLat = target.lat,
            camLon = target.lon,
            isSection = sectionEntry != null,
            sectionAvgSpeed = sectionAvg
        )
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
