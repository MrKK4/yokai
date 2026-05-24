package eu.kanade.tachiyomi.ui.recents

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class HistoryBucket(
    val id: String,
    private val minAgeDays: Long,
    private val maxAgeDays: Long?,
) {
    Today("today", 0, 0),
    Yesterday("yesterday", 1, 1),
    UpTo7Days("up_to_7", 2, 7),
    UpTo14Days("up_to_14", 8, 14),
    UpTo30Days("up_to_30", 15, 30),
    UpTo60Days("up_to_60", 31, 60),
    UpTo90Days("up_to_90", 61, 90),
    UpTo120Days("up_to_120", 91, 120),
    Over120Days("over_120", 121, null),
    ;

    fun contains(ageDays: Long): Boolean =
        ageDays >= minAgeDays && (maxAgeDays == null || ageDays <= maxAgeDays)

    companion object {
        fun fromLastRead(
            lastReadMillis: Long,
            nowMillis: Long = System.currentTimeMillis(),
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): HistoryBucket {
            val readDate = Instant.ofEpochMilli(lastReadMillis)
                .atZone(zoneId)
                .toLocalDate()
            val nowDate = Instant.ofEpochMilli(nowMillis)
                .atZone(zoneId)
                .toLocalDate()
            return fromDates(readDate, nowDate)
        }

        fun fromDates(readDate: LocalDate, nowDate: LocalDate): HistoryBucket {
            val ageDays = ChronoUnit.DAYS.between(readDate, nowDate).coerceAtLeast(0)
            return entries.first { it.contains(ageDays) }
        }

        fun fromId(id: String): HistoryBucket? =
            entries.firstOrNull { it.id == id }
    }
}
