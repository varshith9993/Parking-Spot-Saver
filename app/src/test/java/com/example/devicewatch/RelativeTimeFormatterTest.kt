package com.example.devicewatch

import com.example.devicewatch.model.RelativeTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class RelativeTimeFormatterTest {

    private companion object {
        const val MIN_MS = 60_000L
        const val HOUR_MS = 60L * MIN_MS
        const val DAY_MS = 24L * HOUR_MS
    }

    @Test
    fun formatsRelativePastIntervalsWithSingularAndPluralGrammar() {
        val now = calendarMillis(2026, Calendar.NOVEMBER, 18, 16, 30)

        assertEquals("--", RelativeTimeFormatter.format(0L, now))
        assertEquals("Just now", RelativeTimeFormatter.format(now - 35_000L, now))
        assertEquals("1 minute ago", RelativeTimeFormatter.format(now - MIN_MS, now))
        assertEquals("5 minutes ago", RelativeTimeFormatter.format(now - 5 * MIN_MS, now))
        assertEquals("25 minutes ago", RelativeTimeFormatter.format(now - 25 * MIN_MS, now))
        assertEquals("1 hour ago", RelativeTimeFormatter.format(now - HOUR_MS, now))
        assertEquals("3 hours ago", RelativeTimeFormatter.format(now - 3 * HOUR_MS, now))

        val yesterday = calendarMillis(2026, Calendar.NOVEMBER, 17, 16, 30)
        assertEquals("Yesterday", RelativeTimeFormatter.format(yesterday, now))

        val twoDaysAgo = calendarMillis(2026, Calendar.NOVEMBER, 16, 16, 30)
        assertEquals("2 days ago", RelativeTimeFormatter.format(twoDaysAgo, now))

        assertEquals("5 days ago", RelativeTimeFormatter.format(now - 5 * DAY_MS, now))
        assertEquals("1 week ago", RelativeTimeFormatter.format(now - 10 * DAY_MS, now))
        assertEquals("2 weeks ago", RelativeTimeFormatter.format(now - 14 * DAY_MS, now))
        assertEquals("1 month ago", RelativeTimeFormatter.format(now - 30 * DAY_MS, now))
        assertEquals("3 months ago", RelativeTimeFormatter.format(now - 90 * DAY_MS, now))
        assertEquals("1 year ago", RelativeTimeFormatter.format(now - 365 * DAY_MS, now))
        assertEquals("2 years ago", RelativeTimeFormatter.format(now - 730 * DAY_MS, now))
    }

    @Test
    fun handlesFutureTimestampsGracefully() {
        val now = calendarMillis(2026, Calendar.NOVEMBER, 18, 16, 30)

        assertEquals("Just now", RelativeTimeFormatter.format(now + 20_000L, now))
        assertEquals("In 5 minutes", RelativeTimeFormatter.format(now + 5 * MIN_MS, now))
        assertEquals("In 2 hours", RelativeTimeFormatter.format(now + 2 * HOUR_MS, now))
        assertEquals("Tomorrow", RelativeTimeFormatter.format(now + DAY_MS, now))
        assertEquals("In 3 days", RelativeTimeFormatter.format(now + 3 * DAY_MS, now))
    }

    private fun calendarMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
