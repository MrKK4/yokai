package eu.kanade.tachiyomi.ui.recents

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistoryBucketTest {

    private val today = LocalDate.of(2026, 5, 23)

    @Test
    fun `history buckets are exclusive by local calendar day`() {
        assertEquals(HistoryBucket.Today, bucket(ageDays = 0))
        assertEquals(HistoryBucket.Yesterday, bucket(ageDays = 1))
        assertEquals(HistoryBucket.UpTo7Days, bucket(ageDays = 2))
        assertEquals(HistoryBucket.UpTo7Days, bucket(ageDays = 7))
        assertEquals(HistoryBucket.UpTo14Days, bucket(ageDays = 8))
        assertEquals(HistoryBucket.UpTo14Days, bucket(ageDays = 14))
        assertEquals(HistoryBucket.UpTo30Days, bucket(ageDays = 15))
        assertEquals(HistoryBucket.UpTo30Days, bucket(ageDays = 30))
        assertEquals(HistoryBucket.UpTo60Days, bucket(ageDays = 31))
        assertEquals(HistoryBucket.UpTo60Days, bucket(ageDays = 60))
        assertEquals(HistoryBucket.UpTo90Days, bucket(ageDays = 61))
        assertEquals(HistoryBucket.UpTo90Days, bucket(ageDays = 90))
        assertEquals(HistoryBucket.UpTo120Days, bucket(ageDays = 91))
        assertEquals(HistoryBucket.UpTo120Days, bucket(ageDays = 120))
        assertEquals(HistoryBucket.Over120Days, bucket(ageDays = 121))
    }

    @Test
    fun `future timestamps stay in today bucket`() {
        assertEquals(HistoryBucket.Today, bucket(ageDays = -1))
    }

    private fun bucket(ageDays: Long): HistoryBucket =
        HistoryBucket.fromDates(today.minusDays(ageDays), today)
}
