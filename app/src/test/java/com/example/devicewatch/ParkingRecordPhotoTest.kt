package com.example.devicewatch

import com.example.devicewatch.model.ParkingPhotoItem
import com.example.devicewatch.model.ParkingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingRecordPhotoTest {

    @Test
    fun parkingRecordCapsPhotosAtFiveAndPreservesPrimaryCompatibility() {
        val items = (1..7).map { idx ->
            ParkingPhotoItem(
                "/data/photos/spot_$idx.jpg",
                1_700_000_000_000L + idx,
                12.9716 + idx * 0.001,
                77.5946 + idx * 0.001,
                0L
            )
        }

        val record = ParkingRecord(
            "rec-1",
            12.9716,
            77.5946,
            "Basement B2",
            "221B Baker St",
            1_700_000_000_000L,
            "Near lift",
            items,
            false
        )

        assertEquals(ParkingPhotoItem.MAX_PHOTOS_PER_RECORD, record.photoCount)
        assertEquals(5, record.photos.size)
        assertEquals("/data/photos/spot_1.jpg", record.photoPath)
        assertEquals("/data/photos/spot_5.jpg", record.photos.last().filePath)
        assertFalse(record.isSaved)
        assertTrue(record.hasPhotoLocation())
    }

    @Test
    fun parkingRecordWithNoPhotosHasEmptyListAndNullLegacyPath() {
        val record = ParkingRecord.createNew(12.9716, 77.5946, 1_700_000_000_000L)
        assertEquals(0, record.photoCount)
        assertTrue(record.photos.isEmpty())
        assertNull(record.photoPath)
        assertFalse(record.isSaved)
    }
}
