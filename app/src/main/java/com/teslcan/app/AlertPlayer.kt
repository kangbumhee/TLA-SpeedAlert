package com.teslcan.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin

class AlertPlayer(private val context: Context) {

    /** TTS 엔진 준비 완료 시 1회 (광고 매니저 연동용) */
    var onTtsReady: ((TextToSpeech) -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    private var overSpeedRunnable: Runnable? = null
    private var isOverSpeedWarning = false
    private var muteUntil = 0L

    private var lastPhase = 0
    private var lastSpokenTime = 0L
    private var lastSpokenDistance = -1
    private val minSpeakInterval = 3500L

    var overSpeedThreshold = 10
    var volume = 0.8f
    var voiceStyle = "STANDARD"
    var useBeep = true
    var passChime = true
    var nightAutoVolume = true
    var alertAt1000m = true
    var alertAt500m = true
    var alertAt300m = true
    var alertAt100m = true

    private val camTypeNames = mapOf(
        0 to "과속 단속카메라",
        1 to "고정식 단속카메라",
        2 to "이동식 단속",
        3 to "구간단속",
        4 to "신호 단속",
        5 to "버스전용차로",
        6 to "어린이보호구역"
    )

    fun init() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.KOREAN)
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(1.0f)
                ttsReady = true
                tts?.let { engine -> onTtsReady?.invoke(engine) }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                releaseAudioFocus()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                releaseAudioFocus()
            }
        })
    }

    fun muteFor(ms: Long) {
        muteUntil = System.currentTimeMillis() + ms
    }

    fun isMuted() = System.currentTimeMillis() < muteUntil

    fun getTts(): TextToSpeech? = tts

    private fun roundDistance(d: Int): Int = ((d + 50) / 100) * 100

    private fun effectiveVolume(): Float {
        var v = volume
        if (nightAutoVolume) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h >= 22 || h < 6) v *= 0.7f
        }
        return v.coerceIn(0f, 1f)
    }

    fun speakEvent(message: String) {
        if (!ttsReady || isMuted()) return
        requestAudioFocus()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, effectiveVolume())
        }
        tts?.speak(message, TextToSpeech.QUEUE_ADD, params, "ev")
    }

    fun handleAlert(
        phase: Int,
        distance: Int,
        overspeed: Int,
        speedKmh: Int,
        limitKmh: Int,
        camType: Int = 0,
        @Suppress("UNUSED_PARAMETER") d1: Int = 500,
        @Suppress("UNUSED_PARAMETER") d2: Int = 200,
        zoneTriggered: Int = 0
    ) {
        if (!ttsReady || isMuted()) return

        val now = System.currentTimeMillis()
        val roundedDist = roundDistance(distance)

        if (phase == 0 && lastPhase > 0) {
            stopOverSpeedWarning()
            if (passChime) playPassChime()
            lastPhase = 0
            lastSpokenDistance = -1
            return
        }

        if (overspeed == 1 && limitKmh > 0 &&
            speedKmh >= limitKmh + overSpeedThreshold
        ) {
            if (useBeep) startOverSpeedWarning()
        } else {
            stopOverSpeedWarning()
        }

        if (phase < 0) return
        if (phase > 0 && distance <= 0) return
        if (phase > 0 && zoneTriggered == 0) return
        if (phase > 0 && !isZoneEnabled(zoneTriggered)) return

        if (roundedDist == lastSpokenDistance &&
            now - lastSpokenTime < minSpeakInterval
        ) {
            return
        }

        val message = buildMessage(phase, distance, overspeed, speedKmh, limitKmh, camType)
        if (message != null) {
            Log.d("AlertPlayer", "TTS: $message")
            requestAudioFocus()
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, effectiveVolume())
            }
            val engine = tts
            if (engine == null) {
                Log.w("AlertPlayer", "TTS null!")
                return
            }
            engine.speak(message, TextToSpeech.QUEUE_FLUSH, params, "al")
            lastPhase = phase
            lastSpokenTime = now
            lastSpokenDistance = roundedDist
        }
    }

    /** 설정 화면 음성 테스트 — `voiceStyle`에 따른 `buildMessage` 경로를 태움. 연속 탭 시에도 재생되도록 중복 간격을 리셋한다. */
    fun testAlert(
        phase: Int,
        distance: Int,
        speedKmh: Int,
        limitKmh: Int,
        camType: Int
    ) {
        if (!ttsReady || isMuted()) return
        lastSpokenDistance = -1
        lastSpokenTime = 0L
        handleAlert(phase, distance, 0, speedKmh, limitKmh, camType, 1000, 300, zoneTriggered = 500)
    }

    private fun isZoneEnabled(zone: Int): Boolean {
        return when (zone) {
            1000 -> alertAt1000m
            600 -> alertAt1000m
            500 -> alertAt500m
            300 -> alertAt300m
            100 -> alertAt100m
            else -> true
        }
    }

    private fun buildMessage(
        phase: Int,
        distance: Int,
        overspeed: Int,
        @Suppress("UNUSED_PARAMETER") speedKmh: Int,
        limitKmh: Int,
        camType: Int
    ): String? {
        val camName = camTypeNames[camType] ?: "단속카메라"
        val rd = roundDistance(distance)
        val limitText = if (limitKmh > 0) "${limitKmh}키로" else ""

        return when (voiceStyle) {
            "SHORT" -> when (phase) {
                1, 2 -> "$rd 미터, $camName"
                3 -> "$camName 근접"
                4 -> if (overspeed == 1) "속도를 줄이세요" else null
                else -> null
            }
            "DETAILED" -> when (phase) {
                1 -> "전방 $rd 미터, $limitText $camName 입니다. 제한속도를 확인하세요"
                2 -> "전방 $rd 미터, $limitText $camName. 감속을 준비하세요"
                3 -> "곧 $camName 입니다. $limitText 유지하세요"
                4 -> if (overspeed == 1) {
                    "과속입니다. 제한속도 $limitKmh 초과. 즉시 감속하세요"
                } else {
                    "$camName 통과 중"
                }
                else -> null
            }
            else -> when (phase) {
                1 -> "전방 $rd 미터, $limitText $camName 입니다"
                2 -> "전방 $rd 미터, $camName"
                3 -> "곧 $camName 입니다. 속도를 확인하세요"
                4 -> if (overspeed == 1) "과속입니다. 감속하세요" else null
                else -> null
            }
        }
    }

    private fun startOverSpeedWarning() {
        if (isOverSpeedWarning) return
        isOverSpeedWarning = true
        overSpeedRunnable = object : Runnable {
            override fun run() {
                if (!isOverSpeedWarning || isMuted()) return
                playOverSpeedTone()
                handler.postDelayed(this, 1400)
            }
        }
        handler.post(overSpeedRunnable!!)
    }

    private fun stopOverSpeedWarning() {
        if (!isOverSpeedWarning) return
        isOverSpeedWarning = false
        overSpeedRunnable?.let { handler.removeCallbacks(it) }
        overSpeedRunnable = null
    }

    private fun playOverSpeedTone() {
        Thread {
            try {
                requestAudioFocus()
                val sr = 44100
                val samples = (sr * 0.5).toInt()
                val buf = ShortArray(samples)
                val amp = effectiveVolume() * 0.7
                for (i in 0 until samples) {
                    val t = i.toDouble() / sr
                    val f = if (i < samples / 2) 880.0 else 1200.0
                    buf[i] = (Short.MAX_VALUE * amp * sin(2.0 * Math.PI * f * t)).toInt().toShort()
                }
                playBuffer(buf, sr)
                releaseAudioFocus()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun playPassChime() {
        requestAudioFocus()
        Thread {
            try {
                val sr = 44100
                val total = (sr * 0.35).toInt()
                val buf = ShortArray(total)
                var idx = 0
                val amp = effectiveVolume() * 0.6
                val n1 = (sr * 0.1).toInt()
                for (i in 0 until n1) {
                    if (idx >= total) break
                    val t = i.toDouble() / sr
                    val env = if (i < sr / 100) i.toDouble() / (sr / 100) else 1.0
                    buf[idx++] = (Short.MAX_VALUE * amp * env * sin(2.0 * Math.PI * 1047.0 * t)).toInt().toShort()
                }
                val sil = (sr * 0.05).toInt()
                for (i in 0 until sil) {
                    if (idx >= total) break
                    buf[idx++] = 0
                }
                val n2 = (sr * 0.2).toInt()
                for (i in 0 until n2) {
                    if (idx >= total) break
                    val t = i.toDouble() / sr
                    val fade = 1.0 - (i.toDouble() / n2) * 0.7
                    buf[idx++] = (Short.MAX_VALUE * amp * fade * sin(2.0 * Math.PI * 1319.0 * t)).toInt().toShort()
                }
                playBuffer(buf, sr)
                releaseAudioFocus()
            } catch (_: Exception) {
                releaseAudioFocus()
            }
        }.start()
    }

    private fun playBuffer(buf: ShortArray, sampleRate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        track.play()
        Thread.sleep((buf.size * 1000L / sampleRate) + 50)
        track.release()
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        audioManager?.requestAudioFocus(focusRequest!!)
    }

    private fun releaseAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    /** CameraEngine v2 [AlertInfo] → 기존 handleAlert 브리지 (거리 구간 토글 정합). */
    fun handleSafetyAlert(info: AlertInfo, speedKmh: Int) {
        val zone = if (info.zoneTriggered > 0) {
            info.zoneTriggered
        } else {
            when {
                info.distance <= 100 -> 100
                info.distance <= 300 -> 300
                info.distance <= 500 -> 500
                info.distance <= 600 -> 600
                info.d1 > 600 && info.distance <= 1000 -> 1000
                else -> info.d1.coerceIn(100, 1100)
            }
        }
        handleAlert(
            info.phase,
            info.distance,
            if (info.overspeed) 1 else 0,
            speedKmh,
            info.speedLimit,
            info.camType,
            info.d1,
            info.d2,
            zoneTriggered = zone
        )
    }

    fun stop() {
        tts?.stop()
        stopOverSpeedWarning()
        lastPhase = 0
        lastSpokenDistance = -1
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        stopOverSpeedWarning()
        releaseAudioFocus()
    }
}

fun AlertPlayer.applySettings(s: SettingsStore) {
    overSpeedThreshold = s.overSpeedThreshold
    volume = s.volume / 100f
    voiceStyle = s.voiceStyle
    useBeep = s.useBeep
    passChime = s.passChime
    nightAutoVolume = s.nightAutoVolume
    alertAt1000m = s.alertAt1000m
    alertAt500m = s.alertAt500m
    alertAt300m = s.alertAt300m
    alertAt100m = s.alertAt100m
}
