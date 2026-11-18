package com.example.devicewatch.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;

import java.util.UUID;

/**
 * Structured local parking record representing a saved or historical parking spot
 * along with separated photo capture/import metadata.
 */
public class ParkingRecord {

    @NonNull
    private final String id;
    private final double latitude;
    private final double longitude;
    @Nullable
    private final String address;
    private final long savedTimestamp;
    @Nullable
    private final String note;
    @Nullable
    private final String photoPath;

    private final long originalCaptureTimestamp;
    private final double photoLatitude;
    private final double photoLongitude;
    private final long importTimestamp;

    public ParkingRecord(
            @NonNull String id,
            double latitude,
            double longitude,
            @Nullable String address,
            long savedTimestamp,
            @Nullable String note,
            @Nullable String photoPath,
            long originalCaptureTimestamp,
            double photoLatitude,
            double photoLongitude,
            long importTimestamp
    ) {
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.savedTimestamp = savedTimestamp;
        this.note = note;
        this.photoPath = photoPath;
        this.originalCaptureTimestamp = originalCaptureTimestamp;
        this.photoLatitude = photoLatitude;
        this.photoLongitude = photoLongitude;
        this.importTimestamp = importTimestamp;
    }

    @NonNull
    public static ParkingRecord createNew(double latitude, double longitude, long savedTimestamp) {
        return new ParkingRecord(
                UUID.randomUUID().toString(),
                latitude,
                longitude,
                null,
                savedTimestamp,
                null,
                null,
                0L,
                Double.NaN,
                Double.NaN,
                0L
        );
    }

    @NonNull
    public static ParkingRecord fromParkingSpot(@NonNull String id, @NonNull ParkingSpot spot) {
        return new ParkingRecord(
                id,
                spot.getLatitude(),
                spot.getLongitude(),
                spot.getAddress(),
                spot.getTimestampMillis(),
                spot.getNote(),
                spot.getPhotoUri(),
                spot.getPhotoCapturedAtMillis(),
                spot.getPhotoLatitude(),
                spot.getPhotoLongitude(),
                spot.getPhotoImportedAtMillis()
        );
    }

    @NonNull
    public ParkingSpot toParkingSpot() {
        return new ParkingSpot(
                latitude,
                longitude,
                savedTimestamp,
                address,
                note,
                photoPath,
                originalCaptureTimestamp,
                importTimestamp,
                photoLatitude,
                photoLongitude
        );
    }

    @NonNull
    public String getId() {
        return id;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    @Nullable
    public String getAddress() {
        return address;
    }

    public long getSavedTimestamp() {
        return savedTimestamp;
    }

    @Nullable
    public String getNote() {
        return note;
    }

    @Nullable
    public String getPhotoPath() {
        return photoPath;
    }

    public long getOriginalCaptureTimestamp() {
        return originalCaptureTimestamp;
    }

    public double getPhotoLatitude() {
        return photoLatitude;
    }

    public double getPhotoLongitude() {
        return photoLongitude;
    }

    public long getImportTimestamp() {
        return importTimestamp;
    }

    public boolean hasOriginalCaptureTimestamp() {
        return originalCaptureTimestamp > 0L;
    }

    public boolean hasImportTimestamp() {
        return importTimestamp > 0L;
    }

    public boolean hasPhotoLocation() {
        return DistanceCalculator.isValidCoordinate(photoLatitude, photoLongitude);
    }
}
