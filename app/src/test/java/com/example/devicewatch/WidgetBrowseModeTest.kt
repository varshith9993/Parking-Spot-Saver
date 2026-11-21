package com.example.devicewatch

import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.storage.ParkingStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetBrowseModeTest {

    private fun record(id: String, saved: Boolean = false, timestamp: Long = 1_000L): ParkingRecord {
        return ParkingRecord(
            id,
            17.385,
            78.486,
            "Spot $id",
            null,
            timestamp,
            null,
            emptyList(),
            saved
        )
    }

    @Test
    fun historyModeDefaultsToActiveSpotAndNavigatesBackAndForth() {
        val s3 = record("s3", timestamp = 3_000L)
        val s2 = record("s2", timestamp = 2_000L)
        val s1 = record("s1", timestamp = 1_000L)
        val history = listOf(s3, s2, s1)

        val initial = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            history,
            emptyList(),
            null
        )
        assertEquals("s3", initial.record?.id)
        assertEquals(0, initial.currentIndex)
        assertEquals(3, initial.totalCount)
        assertTrue(initial.canGoPrevious)
        assertFalse(initial.canGoNext)
        assertTrue(initial.isActiveSpot)

        val stepOlder1 = ParkingStorage.computeSteppedBrowseRecordId(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            history,
            emptyList(),
            null,
            1
        )
        assertEquals("s2", stepOlder1)

        val midState = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            history,
            emptyList(),
            stepOlder1
        )
        assertEquals("s2", midState.record?.id)
        assertEquals(1, midState.currentIndex)
        assertTrue(midState.canGoPrevious)
        assertTrue(midState.canGoNext)
        assertFalse(midState.isActiveSpot)

        val stepOlder2 = ParkingStorage.computeSteppedBrowseRecordId(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            history,
            emptyList(),
            stepOlder1,
            1
        )
        assertEquals("s1", stepOlder2)

        val oldestState = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            history,
            emptyList(),
            stepOlder2
        )
        assertEquals("s1", oldestState.record?.id)
        assertEquals(2, oldestState.currentIndex)
        assertFalse(oldestState.canGoPrevious)
        assertTrue(oldestState.canGoNext)

        assertEquals(
            "s1",
            ParkingStorage.computeSteppedBrowseRecordId(
                ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
                s3,
                history,
                emptyList(),
                stepOlder2,
                1
            )
        )

        val stepNewer = ParkingStorage.computeSteppedBrowseRecordId(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            history,
            emptyList(),
            stepOlder2,
            -1
        )
        assertEquals("s2", stepNewer)

        val stepNewest = ParkingStorage.computeSteppedBrowseRecordId(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            history,
            emptyList(),
            stepNewer,
            -1
        )
        assertEquals("s3", stepNewest)

        assertEquals(
            "s3",
            ParkingStorage.computeSteppedBrowseRecordId(
                ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
                s3,
                history,
                emptyList(),
                stepNewest,
                -1
            )
        )
    }

    @Test
    fun historyModeRecallsLatestHistoryWhenActiveCleared() {
        val s2 = record("s2", timestamp = 2_000L)
        val s1 = record("s1", timestamp = 1_000L)
        val history = listOf(s2, s1)

        val cleared = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            null,
            history,
            emptyList(),
            null
        )
        assertNull(cleared.record)
        assertEquals(-1, cleared.currentIndex)
        assertEquals(2, cleared.totalCount)
        assertTrue(cleared.canGoPrevious)
        assertFalse(cleared.canGoNext)
        assertFalse(cleared.isActiveSpot)

        assertNull(
            ParkingStorage.computeSteppedBrowseRecordId(
                ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
                null,
                history,
                emptyList(),
                null,
                -1
            )
        )

        val recalledId = ParkingStorage.computeSteppedBrowseRecordId(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            null,
            history,
            emptyList(),
            null,
            1
        )
        assertEquals("s2", recalledId)

        val recalledState = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            null,
            history,
            emptyList(),
            recalledId
        )
        assertEquals("s2", recalledState.record?.id)
        assertEquals(0, recalledState.currentIndex)
        assertTrue(recalledState.canGoPrevious)
        assertFalse(recalledState.canGoNext)
        assertFalse(recalledState.isActiveSpot)
    }

    @Test
    fun historyModeFallsBackWhenStoredTargetDeletedAndShowsNothingWhenCleared() {
        val s3 = record("s3", saved = true, timestamp = 3_000L)
        val s1 = record("s1", timestamp = 1_000L)

        val recovered = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            listOf(s3, s1),
            listOf(s3),
            "deleted-s2"
        )
        assertEquals("s3", recovered.record?.id)
        assertEquals(0, recovered.currentIndex)
        assertTrue(recovered.isActiveSpot)

        // When history is cleared, History widget mode must show nothing even if a saved active spot exists.
        val clearedHistory = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_HISTORY,
            s3,
            emptyList(),
            listOf(s3),
            null
        )
        assertNull(clearedHistory.record)
        assertEquals(-1, clearedHistory.currentIndex)
        assertEquals(0, clearedHistory.totalCount)
        assertFalse(clearedHistory.canGoPrevious)
        assertFalse(clearedHistory.canGoNext)
        assertFalse(clearedHistory.isActiveSpot)

        // Saved widget mode still shows the preserved bookmarked spot.
        val savedStillVisible = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            s3,
            emptyList(),
            listOf(s3),
            null
        )
        assertEquals("s3", savedStillVisible.record?.id)
    }

    @Test
    fun savedModePinsChosenSavedSpotWhenNewUnbookmarkedSpotAdded() {
        val b2 = record("b2", saved = true, timestamp = 2_000L)
        val b1 = record("b1", saved = true, timestamp = 1_000L)
        val s3 = record("s3", saved = false, timestamp = 3_000L)
        val saved = listOf(b2, b1)

        val defaultSaved = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            s3,
            listOf(s3, b2, b1),
            saved,
            null
        )
        assertEquals("b2", defaultSaved.record?.id)
        assertEquals(0, defaultSaved.currentIndex)
        assertEquals(2, defaultSaved.totalCount)
        assertTrue(defaultSaved.canGoPrevious)
        assertFalse(defaultSaved.canGoNext)
        assertFalse(defaultSaved.isActiveSpot)

        val selectedOlderId = ParkingStorage.computeSteppedBrowseRecordId(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            s3,
            listOf(s3, b2, b1),
            saved,
            null,
            1
        )
        assertEquals("b1", selectedOlderId)

        val s4 = record("s4", saved = false, timestamp = 4_000L)
        val afterUnbookmarkedSave = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            s4,
            listOf(s4, s3, b2, b1),
            saved,
            selectedOlderId
        )
        assertEquals("b1", afterUnbookmarkedSave.record?.id)
        assertEquals(1, afterUnbookmarkedSave.currentIndex)
        assertFalse(afterUnbookmarkedSave.canGoPrevious)
        assertTrue(afterUnbookmarkedSave.canGoNext)
        assertFalse(afterUnbookmarkedSave.isActiveSpot)
    }

    @Test
    fun savedModeUpdatesWhenNewSpotIsSavedWithBookmark() {
        val b3 = record("b3", saved = true, timestamp = 4_000L)
        val b2 = record("b2", saved = true, timestamp = 2_000L)
        val b1 = record("b1", saved = true, timestamp = 1_000L)
        val saved = listOf(b3, b2, b1)

        // When saving a new bookmarked spot, storage resets saved browse cursor to null
        // so the top saved spot (b3) becomes displayed immediately.
        val afterBookmarkedSave = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            b3,
            saved,
            saved,
            null
        )
        assertEquals("b3", afterBookmarkedSave.record?.id)
        assertEquals(0, afterBookmarkedSave.currentIndex)
        assertEquals(3, afterBookmarkedSave.totalCount)
        assertTrue(afterBookmarkedSave.canGoPrevious)
        assertFalse(afterBookmarkedSave.canGoNext)
        assertTrue(afterBookmarkedSave.isActiveSpot)
    }

    @Test
    fun savedModeEmptyAndUnbookmarkFallback() {
        val s1 = record("s1", saved = false, timestamp = 1_000L)
        val emptySaved = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            s1,
            listOf(s1),
            emptyList(),
            null
        )
        assertNull(emptySaved.record)
        assertEquals(-1, emptySaved.currentIndex)
        assertEquals(0, emptySaved.totalCount)
        assertFalse(emptySaved.canGoPrevious)
        assertFalse(emptySaved.canGoNext)

        val b1 = record("b1", saved = true, timestamp = 1_000L)
        val fallbackAfterUnbookmark = ParkingStorage.resolveWidgetBrowseState(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            s1,
            listOf(s1, b1),
            listOf(b1),
            "b2"
        )
        assertEquals("b1", fallbackAfterUnbookmark.record?.id)
        assertEquals(0, fallbackAfterUnbookmark.currentIndex)
    }

    @Test
    fun normalizeWidgetBrowseModeHandlesUnknownValues() {
        assertEquals(ParkingStorage.WIDGET_BROWSE_MODE_HISTORY, ParkingStorage.normalizeWidgetBrowseMode(null))
        assertEquals(ParkingStorage.WIDGET_BROWSE_MODE_HISTORY, ParkingStorage.normalizeWidgetBrowseMode(""))
        assertEquals(ParkingStorage.WIDGET_BROWSE_MODE_HISTORY, ParkingStorage.normalizeWidgetBrowseMode("other"))
        assertEquals(
            ParkingStorage.WIDGET_BROWSE_MODE_SAVED,
            ParkingStorage.normalizeWidgetBrowseMode(ParkingStorage.WIDGET_BROWSE_MODE_SAVED)
        )
    }
}
