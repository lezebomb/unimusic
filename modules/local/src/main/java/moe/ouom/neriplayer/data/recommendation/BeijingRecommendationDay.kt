package moe.ouom.neriplayer.data.recommendation

import java.time.Instant
import java.time.ZoneId

/** The recommendation calendar is fixed to Beijing time, independent of the device time zone. */
object BeijingRecommendationDay {
    private val zone = ZoneId.of("Asia/Shanghai")

    fun key(nowMillis: Long): String = date(nowMillis).toString()

    fun startMillis(nowMillis: Long): Long = date(nowMillis).atStartOfDay(zone).toInstant().toEpochMilli()

    fun nextStartMillis(nowMillis: Long): Long = date(nowMillis).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun date(nowMillis: Long) = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
}
