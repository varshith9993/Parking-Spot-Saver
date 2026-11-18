package com.example.devicewatch.model;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Groups ParkingRecord items into Today, Yesterday, and Earlier buckets
 * using the device's local timezone.
 */
public final class HistoryDateGrouper {

    public static final int CATEGORY_TODAY = 0;
    public static final int CATEGORY_YESTERDAY = 1;
    public static final int CATEGORY_EARLIER = 2;

    public static final class ListRow {
        public final boolean isHeader;
        public final String headerTitle;
        public final ParkingRecord record;

        private ListRow(boolean isHeader, String headerTitle, ParkingRecord record) {
            this.isHeader = isHeader;
            this.headerTitle = headerTitle;
            this.record = record;
        }

        @NonNull
        public static ListRow header(@NonNull String title) {
            return new ListRow(true, title, null);
        }

        @NonNull
        public static ListRow item(@NonNull ParkingRecord record) {
            return new ListRow(false, null, record);
        }
    }

    private HistoryDateGrouper() {
    }

    public static int classifyTimestamp(long timestampMillis, long referenceNowMillis) {
        if (timestampMillis <= 0L) {
            return CATEGORY_EARLIER;
        }
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestampMillis);

        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(referenceNowMillis);
        if (isSameDay(target, today)) {
            return CATEGORY_TODAY;
        }

        Calendar yesterday = Calendar.getInstance();
        yesterday.setTimeInMillis(referenceNowMillis);
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(target, yesterday)) {
            return CATEGORY_YESTERDAY;
        }

        return CATEGORY_EARLIER;
    }

    @NonNull
    public static List<ListRow> buildGroupedRows(
            @NonNull List<ParkingRecord> records,
            long referenceNowMillis,
            @NonNull String todayLabel,
            @NonNull String yesterdayLabel,
            @NonNull String earlierLabel
    ) {
        List<ParkingRecord> todayList = new ArrayList<>();
        List<ParkingRecord> yesterdayList = new ArrayList<>();
        List<ParkingRecord> earlierList = new ArrayList<>();

        for (ParkingRecord record : records) {
            int category = classifyTimestamp(record.getSavedTimestamp(), referenceNowMillis);
            if (category == CATEGORY_TODAY) {
                todayList.add(record);
            } else if (category == CATEGORY_YESTERDAY) {
                yesterdayList.add(record);
            } else {
                earlierList.add(record);
            }
        }

        List<ListRow> rows = new ArrayList<>();
        appendSection(rows, todayLabel, todayList);
        appendSection(rows, yesterdayLabel, yesterdayList);
        appendSection(rows, earlierLabel, earlierList);
        return rows;
    }

    private static void appendSection(
            @NonNull List<ListRow> rows,
            @NonNull String label,
            @NonNull List<ParkingRecord> items
    ) {
        if (items.isEmpty()) {
            return;
        }
        rows.add(ListRow.header(label));
        for (ParkingRecord item : items) {
            rows.add(ListRow.item(item));
        }
    }

    private static boolean isSameDay(@NonNull Calendar first, @NonNull Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }
}
