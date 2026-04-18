package com.teslcan.app

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("camalert_settings", Context.MODE_PRIVATE)

    var profile: String
        get() = prefs.getString("profile", "STANDARD") ?: "STANDARD"
        set(v) = prefs.edit().putString("profile", v).apply()

    var enableFixed: Boolean
        get() = prefs.getBoolean("en_fixed", true)
        set(v) = prefs.edit().putBoolean("en_fixed", v).apply()

    var enableMobile: Boolean
        get() = prefs.getBoolean("en_mobile", false)
        set(v) = prefs.edit().putBoolean("en_mobile", v).apply()

    var enableSection: Boolean
        get() = prefs.getBoolean("en_section", true)
        set(v) = prefs.edit().putBoolean("en_section", v).apply()

    var enableRedLight: Boolean
        get() = prefs.getBoolean("en_redlight", true)
        set(v) = prefs.edit().putBoolean("en_redlight", v).apply()

    var enableChildZone: Boolean
        get() = prefs.getBoolean("en_child", true)
        set(v) = prefs.edit().putBoolean("en_child", v).apply()

    var enableBusLane: Boolean
        get() = prefs.getBoolean("en_buslane", false)
        set(v) = prefs.edit().putBoolean("en_buslane", v).apply()

    var distanceMode: String
        get() = prefs.getString("dist_mode", "DYNAMIC") ?: "DYNAMIC"
        set(v) = prefs.edit().putString("dist_mode", v).apply()

    var overSpeedThreshold: Int
        get() = prefs.getInt("overspeed", 10)
        set(v) = prefs.edit().putInt("overspeed", v).apply()

    var volume: Int
        get() = prefs.getInt("volume", 80)
        set(v) = prefs.edit().putInt("volume", v).apply()

    var voiceStyle: String
        get() = prefs.getString("voice_style", "STANDARD") ?: "STANDARD"
        set(v) = prefs.edit().putString("voice_style", v).apply()

    var nightAutoVolume: Boolean
        get() = prefs.getBoolean("night_auto", true)
        set(v) = prefs.edit().putBoolean("night_auto", v).apply()

    var hudMode: Boolean
        get() = prefs.getBoolean("hud_mode", false)
        set(v) = prefs.edit().putBoolean("hud_mode", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen", true)
        set(v) = prefs.edit().putBoolean("keep_screen", v).apply()

    var useBeep: Boolean
        get() = prefs.getBoolean("use_beep", true)
        set(v) = prefs.edit().putBoolean("use_beep", v).apply()

    var passChime: Boolean
        get() = prefs.getBoolean("pass_chime", true)
        set(v) = prefs.edit().putBoolean("pass_chime", v).apply()

    var alertAt1000m: Boolean
        get() = prefs.getBoolean("alertAt1000m", true)
        set(v) = prefs.edit().putBoolean("alertAt1000m", v).apply()

    var alertAt500m: Boolean
        get() = prefs.getBoolean("alertAt500m", true)
        set(v) = prefs.edit().putBoolean("alertAt500m", v).apply()

    var alertAt300m: Boolean
        get() = prefs.getBoolean("alertAt300m", true)
        set(v) = prefs.edit().putBoolean("alertAt300m", v).apply()

    var alertAt100m: Boolean
        get() = prefs.getBoolean("alertAt100m", true)
        set(v) = prefs.edit().putBoolean("alertAt100m", v).apply()
}
