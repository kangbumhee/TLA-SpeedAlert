package com.teslcan.app

/**
 * 카카오내비 KNSafetyCode 체계 참고. 공공데이터 CSV의 type 필드·레거시 cam_type 매핑에 사용.
 */
enum class SafetyCode(val code: Int, val label: String, val priority: Int) {
    TRAFFIC_ACCIDENT_POS(0, "교통사고 다발", 4),
    SHARP_TURN(1, "급회전", 5),
    CHILDREN_ZONE(11, "어린이 보호구역", 2),
    STEEP_DOWNHILL(13, "급경사 내리막", 5),
    DROWSY_ACCIDENT(16, "졸음운전 사고", 4),
    FROZEN_ROAD(24, "상습 결빙", 5),

    VIOLATION_CAMERA(80, "기타 단속", 5),
    MOVABLE_SPEED(81, "이동식 과속", 2),
    FIXED_SPEED(82, "고정식 과속", 1),
    TRAFFIC_COLLECTION(83, "정보수집", 10),
    BUSLANE(84, "버스전용차로", 7),
    OVERLOAD(85, "과적 단속", 8),
    SIGNAL_AND_SPEED(86, "신호+과속", 1),
    PARKING(87, "주정차", 9),
    CARGO(88, "적재불량", 8),
    BUSLANE_AND_SPEED(89, "버스+신호", 6),
    SIGNAL(90, "신호위반", 3),
    LANE_AND_SPEED(91, "차로+과속", 2),
    SECTION_IN(92, "구간단속 시점", 1),
    SECTION_OUT(93, "구간단속 종점", 1),
    SHOULDER(94, "갓길 단속", 6),
    CUT_IN(95, "끼어들기", 6),
    SECTION_ZONE(96, "구간단속 구간", 1),
    DRIVING_LANE(97, "지정차로", 6),
    LANE_CHANGE_IN(98, "차로변경 시점", 4),
    LANE_CHANGE_OUT(99, "차로변경 종점", 4),
    BOXED_SPEED(100, "박스형 과속", 1),
    SEATBELT(101, "안전벨트", 7),
    BACKWARD_SPEED(102, "후면 과속", 3),
    BACKWARD_SIGNAL_SPEED(103, "후면 신호+과속", 3),
    OLD_DIESEL(104, "노후경유차", 9),
    SECTION_IN_BACKWARD(105, "구간시점(후면)", 3),
    SECTION_OUT_BACKWARD(106, "구간종점(후면)", 3),

    UNKNOWN(-1, "알 수 없음", 99);

    companion object {
        private val byCode = SafetyCode.values().associateBy { it.code }

        fun fromCode(code: Int): SafetyCode = byCode[code] ?: SafetyCode.UNKNOWN

        /** CSV 텍스트 type 컬럼(한글 설명) → SafetyCode */
        fun fromCsvType(csvType: String): SafetyCode {
            val t = csvType.trim()
            val norm = t.replace(" ", "")
            when {
                norm.contains("+") -> return SafetyCode.SIGNAL_AND_SPEED
            }
            when (norm) {
                "1", "01" -> return SafetyCode.FIXED_SPEED
                "2", "02" -> return SafetyCode.SIGNAL
                "01+02", "02+01", "1+2", "2+1" -> return SafetyCode.SIGNAL_AND_SPEED
                "3", "03" -> return SafetyCode.BUSLANE
                "4", "04" -> return SafetyCode.PARKING
                "5", "05" -> return SafetyCode.OVERLOAD
                "6", "06" -> return SafetyCode.CARGO
                "7", "07" -> return SafetyCode.SHOULDER
                "99" -> return SafetyCode.VIOLATION_CAMERA
            }
            return when {
                t.contains("고정식") -> SafetyCode.FIXED_SPEED
                t.contains("이동식") -> SafetyCode.MOVABLE_SPEED
                Regex("신호.*과속").containsMatchIn(t) -> SafetyCode.SIGNAL_AND_SPEED
                t.contains("구간") && t.contains("시점") -> SafetyCode.SECTION_IN
                t.contains("구간") && t.contains("종점") -> SafetyCode.SECTION_OUT
                t.contains("구간") -> SafetyCode.SECTION_ZONE
                t.contains("신호") -> SafetyCode.SIGNAL
                t.contains("박스") -> SafetyCode.BOXED_SPEED
                t.contains("버스") -> SafetyCode.BUSLANE
                t.contains("주정차") -> SafetyCode.PARKING
                t.contains("갓길") -> SafetyCode.SHOULDER
                t.contains("과적") -> SafetyCode.OVERLOAD
                t.contains("적재") -> SafetyCode.CARGO
                t.contains("어린이") -> SafetyCode.CHILDREN_ZONE
                else -> SafetyCode.UNKNOWN
            }
        }

        /** 레거시 DB cam_type (0~6) → SafetyCode */
        fun fromLegacyCamType(camType: Int): SafetyCode = when (camType) {
            0 -> SafetyCode.FIXED_SPEED
            1 -> SafetyCode.FIXED_SPEED
            2 -> SafetyCode.MOVABLE_SPEED
            3 -> SafetyCode.SECTION_IN
            4 -> SafetyCode.SIGNAL
            5 -> SafetyCode.BUSLANE
            6 -> SafetyCode.CHILDREN_ZONE
            else -> SafetyCode.UNKNOWN
        }

        fun isSpeedCamera(code: SafetyCode): Boolean = code.code in 81..106
    }
}

/** 설정 화면용 레거시 cam_type (0~6) */
fun SafetyCode.toLegacyCamType(): Int = when (this) {
    SafetyCode.MOVABLE_SPEED -> 2
    SafetyCode.SECTION_IN, SafetyCode.SECTION_OUT, SafetyCode.SECTION_ZONE -> 3
    SafetyCode.SIGNAL, SafetyCode.SIGNAL_AND_SPEED, SafetyCode.LANE_AND_SPEED, SafetyCode.BACKWARD_SIGNAL_SPEED -> 4
    SafetyCode.BUSLANE, SafetyCode.BUSLANE_AND_SPEED -> 5
    SafetyCode.CHILDREN_ZONE -> 6
    SafetyCode.FIXED_SPEED, SafetyCode.BOXED_SPEED, SafetyCode.VIOLATION_CAMERA, SafetyCode.BACKWARD_SPEED -> 1
    else -> 0
}
