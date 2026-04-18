package com.teslcan.app

object DrivingProfile {

    fun getDistances(profile: String, speedKmh: Int, camType: Int): Pair<Int, Int> {
        val base = when {
            speedKmh < 30 -> Pair(200, 80)
            speedKmh < 50 -> Pair(300, 120)
            speedKmh < 70 -> Pair(500, 200)
            speedKmh < 90 -> Pair(700, 300)
            speedKmh < 110 -> Pair(1000, 500)
            else -> Pair(1200, 600)
        }

        var d1 = base.first
        var d2 = base.second
        when (profile) {
            "BEGINNER" -> {
                d1 += 200
                d2 += 50
            }
            "VETERAN" -> {
                d1 -= 100
                d2 -= 30
            }
            "NIGHT" -> {
                d1 += 100
                d2 += 20
            }
            else -> {}
        }

        when (camType) {
            3 -> d1 += 100
            4 -> d1 -= 100
            2 -> d1 -= 200
            5 -> {}
        }

        return Pair(maxOf(100, d1), maxOf(50, d2))
    }

    fun getLabel(profile: String): String = when (profile) {
        "BEGINNER" -> "초보 모드"
        "VETERAN" -> "베테랑 모드"
        "NIGHT" -> "야간 모드"
        else -> "표준 모드"
    }

    fun isTypeEnabled(camType: Int, settings: SettingsStore): Boolean = when (camType) {
        0, 1 -> settings.enableFixed
        2 -> settings.enableMobile
        3 -> settings.enableSection
        4 -> settings.enableRedLight
        5 -> settings.enableBusLane
        6 -> settings.enableChildZone
        else -> true
    }
}
