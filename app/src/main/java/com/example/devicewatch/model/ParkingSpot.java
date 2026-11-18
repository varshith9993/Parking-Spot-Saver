package com.example.devicewatch.model;

import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;

/**
 * Immutable snapshot of a saved parking spot and optional photo metadata.
 */
public class ParkingSpot {

    private final double latitude;
    private final double longitude;
    private final long timestampMillis;
    @Nullable
    private final String address;
    @Nullable
    private final String note;
    @Nullable
    private final String photoUri;
    private final long photoCapturedAtMillis;
    private final long photoImportedAtMillis;
    private final double photoLatitude;
    private final double photoLongitude;

    public ParkingSpot(
            double latitude,
            double longitude,
            long timestampMillis,
            @Nullable String address,
            @Nullable String note,
            @Nullable String photoUri,
            long photoCapturedAtMillis,
            long photoImportedAtMillis,
            double photoLatitude,
            double photoLongitude
    ) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestampMillis = timestampMillis;
        this.address = address;
        this.note = note;
        this.photoUri = photoUri;
        this.photoCapturedAtMillis = photoCapturedAtMillis;
        this.photoImportedAtMillis = photoImportedAtMillis;
        this.photoLatitude = photoLatitude;
        this.photoLongitude = photoLongitude;
    }

    public ParkingSpot(
            double latitude,
            double longitude,
            long timestampMillis,
            @Nullable String address,
            @Nullable String note,
            @Nullable String photoUri
    ) {
        this(latitude, longitude, timestampMillis, address, note, photoUri, 0L, 0L, Double.NaN, Double.NaN);
    }

    public ParkingSpot(double latitude, double longitude, long timestampMillis) {
        this(latitude, longitude, timestampMillis, null, null, null);
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    @Nullable
    public String getAddress() {
        return address;
    }

    @Nullable
    public String getNote() {
        return note;
    }

    @Nullable
    public String getPhotoUri() {
        return photoUri;
    }

    public long getPhotoCapturedAtMillis() {
        return photoCapturedAtMillis;
    }

    public long getPhotoImportedAtMillis() {
        return photoImportedAtMillis;
    }

    public double getPhotoLatitude() {
        return photoLatitude;
    }

    public double getPhotoLongitude() {
        return photoLongitude;
    }

    public boolean hasPhotoCapturedTimestamp() {
        return photoCapturedAtMillis > 0L;
    }

    public boolean hasPhotoImportedTimestamp() {
        return photoImportedAtMillis > 0L;
    }

    public boolean hasPhotoLocation() {
        return DistanceCalculator.isValidCoordinate(photoLatitude, photoLongitude);
    }
}
