package com.example.devicewatch.model;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.Locale;

/**
 * Formats parking timestamps into dynamic human-readable relative time strings
 * using the record's original saved epoch milliseconds and current wall time.
 */
public final class RelativeTimeFormatter {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    private static final long DAY_MS = 24L * HOUR_MS;
    private static final long HALF_DAY_MS = 12L * HOUR_MS;

    private RelativeTimeFormatter() {
    }

    @NonNull
    public static String format(long savedTimestampMillis) {
        return format(savedTimestampMillis, System.currentTimeMillis());
    }

    @NonNull
    public static String format(long savedTimestampMillis, long referenceNowMillis) {
        if (savedTimestampMillis <= 0L) {
            return "--";
        }
        long diffMillis = referenceNowMillis - savedTimestampMillis;
        if (diffMillis < 0L) {
            return formatFuture(savedTimestampMillis, referenceNowMillis);
        }
        if (diffMillis < MINUTE_MS) {
            return "Just now";
        }
        if (diffMillis < HOUR_MS) {
            long mins = diffMillis / MINUTE_MS;
            return plural(mins, "%d minute ago", "%d minutes ago");
        }

        long calendarDays = calendarDayDifference(savedTimestampMillis, referenceNowMillis);
        if (diffMillis < DAY_MS) {
            if (calendarDays == 1L && diffMillis >= HALF_DAY_MS) {
                return "Yesterday";
            }
            long hours = Math.max(1L, diffMillis / HOUR_MS);
            return plural(hours, "%d hour ago", "%d hours ago");
        }

        long days = Math.max(calendarDays, diffMillis / DAY_MS);
        if (days <= 1L) {
            return "Yesterday";
        }
        if (days < 7L) {
            return plural(days, "%d day ago", "%d days ago");
        }
        if (days < 30L) {
            long weeks = Math.max(1L, days / 7L);
            return plural(weeks, "%d week ago", "%d weeks ago");
        }
        if (days < 365L) {
            long months = Math.min(11L, Math.max(1L, days / 30L));
            return plural(months, "%d month ago", "%d months ago");
        }
        long years = Math.max(1L, days / 365L);
        return plural(years, "%d year ago", "%d years ago");
    }

    @NonNull
    private static String formatFuture(long targetTimestampMillis, long referenceNowMillis) {
        long aheadMillis = targetTimestampMillis - referenceNowMillis;
        if (aheadMillis < MINUTE_MS) {
            return "Just now";
        }
        if (aheadMillis < HOUR_MS) {
            long mins = Math.max(1L, aheadMillis / MINUTE_MS);
            return plural(mins, "In %d minute", "In %d minutes");
        }

        long calendarDays = calendarDayDifference(referenceNowMillis, targetTimestampMillis);
        if (aheadMillis < DAY_MS) {
            if (calendarDays == 1L && aheadMillis >= HALF_DAY_MS) {
                return "Tomorrow";
            }
            long hours = Math.max(1L, aheadMillis / HOUR_MS);
            return plural(hours, "In %d hour", "In %d hours");
        }

        long days = Math.max(calendarDays, aheadMillis / DAY_MS);
        if (days <= 1L) {
            return "Tomorrow";
        }
        if (days < 7L) {
            return plural(days, "In %d day", "In %d days");
        }
        if (days < 30L) {
            long weeks = Math.max(1L, days / 7L);
            return plural(weeks, "In %d week", "In %d weeks");
        }
        if (days < 365L) {
            long months = Math.min(11L, Math.max(1L, days / 30L));
            return plural(months, "In %d month", "In %d months");
        }
        long years = Math.max(1L, days / 365L);
        return plural(years, "In %d year", "In %d years");
    }

    private static long calendarDayDifference(long earlierMillis, long laterMillis) {
        long startMidnight = startOfDayMillis(earlierMillis);
        long endMidnight = startOfDayMillis(laterMillis);
        if (endMidnight <= startMidnight) {
            return 0L;
        }
        return Math.round((endMidnight - startMidnight) / (double) DAY_MS);
    }

    private static long startOfDayMillis(long timestampMillis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestampMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @NonNull
    private static String plural(long value, @NonNull String singularFormat, @NonNull String pluralFormat) {
        return String.format(Locale.US, value == 1L ? singularFormat : pluralFormat, value);
    }
}
