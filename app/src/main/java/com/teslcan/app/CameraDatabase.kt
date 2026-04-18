package com.teslcan.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class CameraRecord(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val speedLimit: Int,
    val safetyCode: SafetyCode,
    val direction: Float?,
    val roadName: String?,
    val sectionLengthM: Int? = null,
    val sourceId: String? = null
)

class CameraDatabase(private val context: Context) : SQLiteOpenHelper(context, "cameras.db", null, 4) {

    companion object {
        private const val TAG = "CameraDB"
        private const val TABLE = "cameras"
        private const val PEEK_BYTES = 65536
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                speedLimit INTEGER DEFAULT 0,
                safetyCode INTEGER DEFAULT 82,
                direction REAL,
                roadName TEXT,
                sectionLength INTEGER,
                sourceId TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cameras_lat ON $TABLE(lat)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cameras_lon ON $TABLE(lon)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 4) {
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN sectionLength INTEGER")
            } catch (e: Exception) {
                Log.w(TAG, "sectionLength 컬럼", e)
            }
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN sourceId TEXT")
            } catch (e: Exception) {
                Log.w(TAG, "sourceId 컬럼", e)
            }
        }
        if (old < 3) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    fun getCameraCount(): Int {
        val db = readableDatabase
        val c = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * 공공데이터 표준 CSV(헤더에 위도·경도·단속구분)면 헤더 매핑 임포트, 아니면 레거시 5컬럼.
     */
    fun importFromAssetsAuto(assetFileName: String): Int {
        val charset = detectCharsetForAsset(assetFileName)
        val count = importPublicCsvStreaming(assetFileName, charset)
        return if (count >= 0) count else loadLegacyFromAssets(assetFileName)
    }

    /**
     * UTF-8 우선 → 앞부분에 `위도` 또는 `카메라` 없으면 MS949.
     */
    private fun detectCharsetForAsset(assetFileName: String): Charset {
        val chunk = context.assets.open(assetFileName).use { ins ->
            val buf = ByteArray(PEEK_BYTES)
            val n = ins.read(buf)
            buf.copyOf(n.coerceAtLeast(0))
        }
        val asUtf8 = decodeLenientUtf8(chunk)
        if (asUtf8.contains("위도") || asUtf8.contains("카메라")) return Charsets.UTF_8
        val asMs949 = String(chunk, Charset.forName("MS949"))
        if (asMs949.contains("위도") || asMs949.contains("카메라")) return Charset.forName("MS949")
        return Charsets.UTF_8
    }

    private fun decodeLenientUtf8(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8)

    /**
     * 공공데이터: 헤더로 컬럼 인덱스 결정. 실패 시 -1 반환(호출측에서 레거시로 폴백).
     * @return 삽입 건수, 또는 헤더 불일치 시 -1
     */
    fun importPublicCsvFromAsset(assetFileName: String, charset: Charset? = null): Int {
        val cs = charset ?: detectCharsetForAsset(assetFileName)
        return importPublicCsvStreaming(assetFileName, cs)
    }

    private fun importPublicCsvStreaming(assetFileName: String, charset: Charset): Int {
        val db = writableDatabase
        var count = 0
        try {
            BufferedReader(InputStreamReader(context.assets.open(assetFileName), charset)).use { reader ->
                val headerLine = reader.readLine() ?: return -1
                val headerCells = parseCsvLine(headerLine)
                val colMap = buildColumnIndexMap(headerCells) ?: return -1
                val iLat = colMap["위도"] ?: return -1
                val iLon = colMap["경도"] ?: return -1
                val iEnforcement = colMap["단속구분"] ?: return -1
                val iSpeedLimit = colMap["제한속도"] ?: return -1
                val iSectionPos = colMap["단속구간위치구분"] ?: -1
                val iSectionLen = colMap["과속단속구간길이"] ?: -1
                val iProtectZone = colMap["보호구역구분"] ?: -1
                val iRoadName = colMap["도로노선명"] ?: -1
                val iDirection = colMap["도로노선방향"] ?: -1
                val iSourceId = colMap["카메라관리번호"]
                    ?: colMap["무인교통단속카메라관리번호"]
                    ?: -1

                Log.i(
                    TAG,
                    "CSV 헤더 매핑: 위도=$iLat 경도=$iLon 단속구분=$iEnforcement 제한속도=$iSpeedLimit " +
                        "구간위치=$iSectionPos 구간길이=$iSectionLen 보호구역=$iProtectZone"
                )

                db.delete(TABLE, null, null)
                db.beginTransaction()
                try {
                    reader.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        val parts = parseCsvLine(line)
                        if (parts.size <= maxOf(iLat, iLon, iEnforcement, iSpeedLimit)) return@forEachLine
                        try {
                            val lat = parts[iLat].trim().toDoubleOrNull() ?: return@forEachLine
                            val lon = parts[iLon].trim().toDoubleOrNull() ?: return@forEachLine
                            if (lat !in 33.0..39.0 || lon !in 124.0..132.0) return@forEachLine

                            val enforcement = if (iEnforcement >= 0) parts[iEnforcement].trim() else ""
                            val speedLimit = if (iSpeedLimit >= 0) {
                                parts[iSpeedLimit].trim().toIntOrNull() ?: 0
                            } else {
                                0
                            }
                            val sectionPos = if (iSectionPos >= 0) parts.getOrNull(iSectionPos)?.trim().orEmpty() else ""
                            val sectionLen = parseOptionalInt(parts, iSectionLen)
                            val protectZone = if (iProtectZone >= 0) parts.getOrNull(iProtectZone)?.trim().orEmpty() else ""
                            val roadName = if (iRoadName >= 0) {
                                parts.getOrNull(iRoadName)?.trim().orEmpty().ifEmpty { null }
                            } else {
                                null
                            }
                            val dirRaw = if (iDirection >= 0) parts.getOrNull(iDirection)?.trim().orEmpty() else ""
                            val direction = parseDirectionFromRoad(dirRaw)
                            val sourceId = if (iSourceId >= 0) {
                                parts.getOrNull(iSourceId)?.trim().takeIf { !it.isNullOrEmpty() }
                            } else {
                                null
                            }

                            val safety = mapEnforcementToSafetyCode(enforcement, sectionPos, protectZone)
                            val cv = ContentValues().apply {
                                put("lat", lat)
                                put("lon", lon)
                                put("speedLimit", speedLimit)
                                put("safetyCode", safety.code)
                                put("direction", direction)
                                put("roadName", roadName)
                                if (sectionLen != null) put("sectionLength", sectionLen)
                                else putNull("sectionLength")
                                if (sourceId != null) put("sourceId", sourceId)
                                else putNull("sourceId")
                            }
                            db.insert(TABLE, null, cv)
                            count++
                        } catch (e: Exception) {
                            Log.w(TAG, "공공 CSV 라인 스킵: ${e.message}")
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            Log.i(TAG, "공공데이터 CSV 임포트: $count 건 ($charset)")
        } catch (e: Exception) {
            Log.e(TAG, "공공 CSV 실패", e)
            return -1
        }
        return count
    }

    fun loadLegacyFromAssets(assetFileName: String): Int {
        val db = writableDatabase
        db.delete(TABLE, null, null)
        var count = 0
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open(assetFileName), Charsets.UTF_8))
            reader.readLine()
            db.beginTransaction()
            try {
                reader.forEachLine { line ->
                    try {
                        val cols = line.split(",")
                        if (cols.size >= 5) {
                            val lat = cols[0].trim().toDoubleOrNull() ?: return@forEachLine
                            val lon = cols[1].trim().toDoubleOrNull() ?: return@forEachLine
                            val limit = cols[2].trim().toIntOrNull() ?: 0
                            val head = cols[3].trim().toIntOrNull() ?: -1
                            val camType = cols[4].trim().toIntOrNull() ?: 0
                            if (lat > 33.0 && lat < 39.0 && lon > 124.0 && lon < 132.0) {
                                val code = SafetyCode.fromLegacyCamType(camType)
                                val cv = ContentValues().apply {
                                    put("lat", lat)
                                    put("lon", lon)
                                    put("speedLimit", limit)
                                    put("safetyCode", code.code)
                                    put("direction", if (head < 0) null else head.toFloat())
                                    putNull("roadName")
                                    putNull("sectionLength")
                                    putNull("sourceId")
                                }
                                db.insert(TABLE, null, cv)
                                count++
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CSV 라인 오류: $line", e)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            reader.close()
            Log.i(TAG, "레거시 CSV 로드: $count 건")
        } catch (e: Exception) {
            Log.e(TAG, "CSV 로드 실패", e)
        }
        return count
    }

    fun loadFromCsvString(csvData: String): Int {
        val db = writableDatabase
        db.delete(TABLE, null, null)
        val lines = csvData.lines()
        if (lines.size < 2) return 0
        val headerCells = parseCsvLine(lines[0])
        val colMap = buildColumnIndexMap(headerCells)
        val isPublic = colMap != null &&
            colMap.containsKey("위도") &&
            colMap.containsKey("경도") &&
            colMap.containsKey("단속구분")
        var count = 0
        db.beginTransaction()
        try {
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                try {
                    if (isPublic && colMap != null) {
                        val parts = parseCsvLine(line)
                        if (!insertPublicRow(db, parts, colMap)) continue
                        count++
                    } else {
                        val cols = line.split(",")
                        if (cols.size >= 5) {
                            val lat = cols[0].trim().toDoubleOrNull() ?: continue
                            val lon = cols[1].trim().toDoubleOrNull() ?: continue
                            val limit = cols[2].trim().toIntOrNull() ?: 0
                            val head = cols[3].trim().toIntOrNull() ?: -1
                            val camType = cols[4].trim().toIntOrNull() ?: 0
                            if (lat > 33.0 && lat < 39.0 && lon > 124.0 && lon < 132.0) {
                                val code = SafetyCode.fromLegacyCamType(camType)
                                val cv = ContentValues().apply {
                                    put("lat", lat)
                                    put("lon", lon)
                                    put("speedLimit", limit)
                                    put("safetyCode", code.code)
                                    put("direction", if (head < 0) null else head.toFloat())
                                    putNull("roadName")
                                    putNull("sectionLength")
                                    putNull("sourceId")
                                }
                                db.insert(TABLE, null, cv)
                                count++
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "loadFromCsvString: $count 건 (public=$isPublic)")
        return count
    }

    private fun insertPublicRow(db: SQLiteDatabase, parts: List<String>, colMap: Map<String, Int>): Boolean {
        val iLat = colMap["위도"] ?: return false
        val iLon = colMap["경도"] ?: return false
        val iEnforcement = colMap["단속구분"] ?: return false
        val iSpeedLimit = colMap["제한속도"] ?: return false
        val iSectionPos = colMap["단속구간위치구분"] ?: -1
        val iSectionLen = colMap["과속단속구간길이"] ?: -1
        val iProtectZone = colMap["보호구역구분"] ?: -1
        val iRoadName = colMap["도로노선명"] ?: -1
        val iDirection = colMap["도로노선방향"] ?: -1
        val iSourceId = colMap["카메라관리번호"]
            ?: colMap["무인교통단속카메라관리번호"]
            ?: -1

        if (parts.size <= maxOf(iLat, iLon, iEnforcement, iSpeedLimit)) return false
        val lat = parts[iLat].trim().toDoubleOrNull() ?: return false
        val lon = parts[iLon].trim().toDoubleOrNull() ?: return false
        if (lat !in 33.0..39.0 || lon !in 124.0..132.0) return false

        val enforcement = parts[iEnforcement].trim()
        val speedLimit = parts[iSpeedLimit].trim().toIntOrNull() ?: 0
        val sectionPos = if (iSectionPos >= 0) parts.getOrNull(iSectionPos)?.trim().orEmpty() else ""
        val sectionLen = parseOptionalInt(parts, iSectionLen)
        val protectZone = if (iProtectZone >= 0) parts.getOrNull(iProtectZone)?.trim().orEmpty() else ""
        val roadName = if (iRoadName >= 0) {
            parts.getOrNull(iRoadName)?.trim().orEmpty().ifEmpty { null }
        } else {
            null
        }
        val dirRaw = if (iDirection >= 0) parts.getOrNull(iDirection)?.trim().orEmpty() else ""
        val direction = parseDirectionFromRoad(dirRaw)
        val sourceId = if (iSourceId >= 0) {
            parts.getOrNull(iSourceId)?.trim().takeIf { !it.isNullOrEmpty() }
        } else {
            null
        }
        val safety = mapEnforcementToSafetyCode(enforcement, sectionPos, protectZone)
        val cv = ContentValues().apply {
            put("lat", lat)
            put("lon", lon)
            put("speedLimit", speedLimit)
            put("safetyCode", safety.code)
            put("direction", direction)
            put("roadName", roadName)
            if (sectionLen != null) put("sectionLength", sectionLen)
            else putNull("sectionLength")
            if (sourceId != null) put("sourceId", sourceId)
            else putNull("sourceId")
        }
        db.insert(TABLE, null, cv)
        return true
    }

    fun findNearbyForMap(lat: Double, lon: Double, radiusM: Int): List<CameraRecord> =
        findNearbyCameras(lat, lon, headingDeg = null, maxDistanceMeters = radiusM.toDouble(), aheadAngle = 360f)

    fun findNearbyCameras(
        lat: Double,
        lon: Double,
        headingDeg: Float?,
        maxDistanceMeters: Double = 1500.0,
        /** 진행 방위가 있을 때 카메라 방위와의 최대 편차(반각). ±45° = 전방 90° 부채꼴 */
        aheadAngle: Float = 45f
    ): List<CameraRecord> {
        val db = readableDatabase
        val dLat = maxDistanceMeters / 111000.0
        val dLon = maxDistanceMeters / (111000.0 * cos(Math.toRadians(lat)))

        val cursor = db.rawQuery(
            """SELECT id, lat, lon, speedLimit, safetyCode, direction, roadName, sectionLength, sourceId
               FROM $TABLE
               WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?""",
            arrayOf(
                (lat - dLat).toString(),
                (lat + dLat).toString(),
                (lon - dLon).toString(),
                (lon + dLon).toString()
            )
        )

        val results = mutableListOf<CameraRecord>()
        cursor.use {
            while (it.moveToNext()) {
                val camLat = it.getDouble(1)
                val camLon = it.getDouble(2)
                val dist = haversineMeters(lat, lon, camLat, camLon)
                if (dist > maxDistanceMeters) continue

                if (headingDeg != null && aheadAngle < 360f) {
                    val bearing = bearingDeg(lat, lon, camLat, camLon)
                    val diff = angleDiff(headingDeg.toDouble(), bearing)
                    if (diff > aheadAngle) continue
                }

                val dirOrNull = if (it.isNull(5)) null else it.getFloat(5)
                val code = SafetyCode.fromCode(it.getInt(4))
                val secLen = if (it.isNull(7)) null else it.getInt(7)
                val src = it.getString(8)
                results.add(
                    CameraRecord(
                        id = it.getLong(0),
                        lat = camLat,
                        lon = camLon,
                        speedLimit = it.getInt(3),
                        safetyCode = code,
                        direction = dirOrNull,
                        roadName = it.getString(6),
                        sectionLengthM = secLen,
                        sourceId = src
                    )
                )
            }
        }
        // 가까운 카메라 우선, 동일 거리면 safetyCode.priority(낮을수록 긴급) 순
        return results.sortedWith(
            compareBy<CameraRecord> { haversineMeters(lat, lon, it.lat, it.lon) }
                .thenBy { it.safetyCode.priority }
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

private fun normalizeHeaderCell(raw: String): String =
    raw.trim().trim('"').replace("\uFEFF", "")

/** 헤더 셀 → (정규화된 이름, 인덱스). 동일 이름 중복 시 첫 번째만 유지 */
private fun buildColumnIndexMap(headerCells: List<String>): Map<String, Int>? {
    if (headerCells.isEmpty()) return null
    val map = LinkedHashMap<String, Int>()
    headerCells.forEachIndexed { i, cell ->
        val key = normalizeHeaderCell(cell)
        if (key.isNotEmpty() && !map.containsKey(key)) map[key] = i
    }
    return map
}

private fun parseOptionalInt(parts: List<String>, index: Int): Int? {
    if (index < 0) return null
    val s = parts.getOrNull(index)?.trim() ?: return null
    if (s.isEmpty()) return null
    return s.toIntOrNull()
}

/** 따옴표 필드 내 쉼표 허용 */
fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    for (ch in line) {
        when {
            ch == '"' -> inQuotes = !inQuotes
            ch == ',' && !inQuotes -> {
                result.add(cur.toString())
                cur.clear()
            }
            else -> cur.append(ch)
        }
    }
    result.add(cur.toString())
    return result
}

/**
 * 공공데이터 `단속구분`: 숫자 코드(01, 02, 01+02, …) 우선, 텍스트는 보조.
 * 구간·보호구역은 별도 컬럼(있을 때만) 반영.
 */
private fun mapEnforcementToSafetyCode(
    enforcement: String,
    sectionPos: String,
    protectZone: String
): SafetyCode {
    val pz = protectZone.trim()
    if (pz.isNotEmpty()) {
        when {
            pz == "02" || pz == "2" -> return SafetyCode.CHILDREN_ZONE
            pz == "01" || pz == "1" -> return SafetyCode.CHILDREN_ZONE
            pz.contains("어린이") || pz.contains("스쿨") ||
                pz.contains("child", ignoreCase = true) -> return SafetyCode.CHILDREN_ZONE
            pz.contains("노인") -> return SafetyCode.CHILDREN_ZONE
        }
    }
    val sp = sectionPos.trim()
    when {
        sp == "01" || sp == "1" || sp.contains("시점") -> return SafetyCode.SECTION_IN
        sp == "02" || sp == "2" || sp.contains("종점") -> return SafetyCode.SECTION_OUT
        sp.contains("구간") -> return SafetyCode.SECTION_ZONE
    }

    val raw = enforcement.trim().replace(" ", "")
    if (raw.isNotEmpty()) {
        if (raw.contains("+")) return SafetyCode.SIGNAL_AND_SPEED
        val num = mapEnforcementNumericCode(raw)
        if (num != null) return num
    }
    val t = enforcement.trim()
    return when {
        t.contains("신호") && t.contains("과속") -> SafetyCode.SIGNAL_AND_SPEED
        t.contains("과속") && t.contains("신호") -> SafetyCode.SIGNAL_AND_SPEED
        t.contains("구간") -> SafetyCode.SECTION_ZONE
        t.contains("과속") || t.contains("속도") -> SafetyCode.FIXED_SPEED
        t.contains("신호") -> SafetyCode.SIGNAL
        t.contains("주정차") -> SafetyCode.PARKING
        t.contains("버스") -> SafetyCode.BUSLANE
        t.contains("적재") -> SafetyCode.CARGO
        t.contains("갓길") -> SafetyCode.SHOULDER
        t.contains("과적") -> SafetyCode.OVERLOAD
        else -> SafetyCode.VIOLATION_CAMERA
    }
}

/**
 * 단속구분: 기관마다 `1`/`01`, `2`/`02` 혼재. 경기 `03`=통행위반 등은 [BUSLANE]로 근사(별도 코드 없음).
 * `99` = 기타.
 */
private fun mapEnforcementNumericCode(raw: String): SafetyCode? {
    val x = raw.replace(" ", "")
    return when (x) {
        "1", "01" -> SafetyCode.FIXED_SPEED
        "2", "02" -> SafetyCode.SIGNAL
        "01+02", "02+01", "1+2", "2+1" -> SafetyCode.SIGNAL_AND_SPEED
        "3", "03" -> SafetyCode.BUSLANE
        "4", "04" -> SafetyCode.PARKING
        "5", "05" -> SafetyCode.OVERLOAD
        "6", "06" -> SafetyCode.CARGO
        "7", "07" -> SafetyCode.SHOULDER
        "99" -> SafetyCode.VIOLATION_CAMERA
        else -> null
    }
}

/**
 * 도로노선방향: 경기도 기준 01 상행·02 하행·03 양방향이나 도로마다 기준이 달라 신뢰 가능한 heading 없음 → 항상 null.
 */
private fun parseDirectionFromRoad(dir: String): Float? {
    val d = dir.trim()
    if (d.isEmpty()) return null
    when {
        d == "01" || d == "1" -> return null
        d == "02" || d == "2" -> return null
        d == "03" || d == "3" -> return null
    }
    if (d.contains("상행") || d.contains("하행") || d.contains("양방향")) return null
    if ((d.contains("동") && d.contains("서")) || (d.contains("남") && d.contains("북"))) return null
    if (d.contains("동향")) return 90f
    if (d.contains("서향")) return 270f
    if (d.contains("북향")) return 0f
    if (d.contains("남향")) return 180f
    return null
}
