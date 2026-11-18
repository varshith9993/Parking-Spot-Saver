package com.example.devicewatch.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Structured local parking record representing a parking spot, optional place label,
 * up to 5 attached photos with separated EXIF metadata, and manual bookmark status.
 */
public class ParkingRecord {

    @NonNull
    private final String id;
    private final double latitude;
    private final double longitude;
    @Nullable
    private final String placeLabel;
    @Nullable
    private final String address;
    private final long savedTimestamp;
    @Nullable
    private final String note;
    @NonNull
    private final List<ParkingPhotoItem> photos;
    private final boolean isSaved;

    public ParkingRecord(
            @NonNull String id,
            double latitude,
            double longitude,
            @Nullable String placeLabel,
            @Nullable String address,
            long savedTimestamp,
            @Nullable String note,
            @Nullable List<ParkingPhotoItem> photos,
            boolean isSaved
    ) {
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeLabel = placeLabel;
        this.address = address;
        this.savedTimestamp = savedTimestamp;
        this.note = note;
        if (photos == null || photos.isEmpty()) {
            this.photos = Collections.emptyList();
        } else {
            int limit = Math.min(photos.size(), ParkingPhotoItem.MAX_PHOTOS_PER_RECORD);
            this.photos = Collections.unmodifiableList(new ArrayList<>(photos.subList(0, limit)));
        }
        this.isSaved = isSaved;
    }

    public ParkingRecord(
            @NonNull String id,
            double latitude,
            double longitude,
            @Nullable String placeLabel,
            @Nullable String address,
            long savedTimestamp,
            @Nullable String note,
            @Nullable String photoPath,
            long originalCaptureTimestamp,
            double photoLatitude,
            double photoLongitude,
            long importTimestamp,
            boolean isSaved
    ) {
        this(
                id,
                latitude,
                longitude,
                placeLabel,
                address,
                savedTimestamp,
                note,
                buildSinglePhotoList(
                        photoPath,
                        originalCaptureTimestamp,
                        photoLatitude,
                        photoLongitude,
                        importTimestamp
                ),
                isSaved
        );
    }

    public ParkingRecord(
            @NonNull String id,
            double latitude,
            double longitude,
            @Nullable String placeLabel,
            @Nullable String address,
            long savedTimestamp,
            @Nullable String note,
            @Nullable String photoPath,
            long originalCaptureTimestamp,
            double photoLatitude,
            double photoLongitude,
            long importTimestamp
    ) {
        this(
                id,
                latitude,
                longitude,
                placeLabel,
                address,
                savedTimestamp,
                note,
                photoPath,
                originalCaptureTimestamp,
                photoLatitude,
                photoLongitude,
                importTimestamp,
                false
        );
    }

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
        this(
                id,
                latitude,
                longitude,
                null,
                address,
                savedTimestamp,
                note,
                photoPath,
                originalCaptureTimestamp,
                photoLatitude,
                photoLongitude,
                importTimestamp,
                false
        );
    }

    @NonNull
    private static List<ParkingPhotoItem> buildSinglePhotoList(
            @Nullable String photoPath,
            long captureTs,
            double lat,
            double lng,
            long importTs
    ) {
        if (photoPath == null || photoPath.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(
                new ParkingPhotoItem(photoPath, captureTs, lat, lng, importTs)
        );
    }

    @NonNull
    public static ParkingRecord createNew(double latitude, double longitude, long savedTimestamp) {
        return createNew(latitude, longitude, savedTimestamp, null, false);
    }

    @NonNull
    public static ParkingRecord createNew(
            double latitude,
            double longitude,
            long savedTimestamp,
            @Nullable String placeLabel
    ) {
        return createNew(latitude, longitude, savedTimestamp, placeLabel, false);
    }

    @NonNull
    public static ParkingRecord createNew(
            double latitude,
            double longitude,
            long savedTimestamp,
            @Nullable String placeLabel,
            boolean isSaved
    ) {
        String normalizedLabel = placeLabel != null && !placeLabel.trim().isEmpty()
                ? placeLabel.trim()
                : null;
        return new ParkingRecord(
                UUID.randomUUID().toString(),
                latitude,
                longitude,
                normalizedLabel,
                null,
                savedTimestamp,
                null,
                Collections.emptyList(),
                isSaved
        );
    }

    @NonNull
    public static ParkingRecord fromParkingSpot(@NonNull String id, @NonNull ParkingSpot spot) {
        return new ParkingRecord(
                id,
                spot.getLatitude(),
                spot.getLongitude(),
                null,
                spot.getAddress(),
                spot.getTimestampMillis(),
                spot.getNote(),
                spot.getPhotoUri(),
                spot.getPhotoCapturedAtMillis(),
                spot.getPhotoLatitude(),
                spot.getPhotoLongitude(),
                spot.getPhotoImportedAtMillis(),
                false
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
                getPhotoPath(),
                getOriginalCaptureTimestamp(),
                getImportTimestamp(),
                getPhotoLatitude(),
                getPhotoLongitude()
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
    public String getPlaceLabel() {
        return placeLabel;
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

    @NonNull
    public List<ParkingPhotoItem> getPhotos() {
        return photos;
    }

    public int getPhotoCount() {
        return photos.size();
    }

    @Nullable
    public String getPhotoPath() {
        return photos.isEmpty() ? null : photos.get(0).getFilePath();
    }

    public long getOriginalCaptureTimestamp() {
        return photos.isEmpty() ? 0L : photos.get(0).getOriginalCaptureTimestamp();
    }

    public double getPhotoLatitude() {
        return photos.isEmpty() ? Double.NaN : photos.get(0).getPhotoLatitude();
    }

    public double getPhotoLongitude() {
        return photos.isEmpty() ? Double.NaN : photos.get(0).getPhotoLongitude();
    }

    public long getImportTimestamp() {
        return photos.isEmpty() ? 0L : photos.get(0).getImportTimestamp();
    }

    public boolean isSaved() {
        return isSaved;
    }

    public boolean hasOriginalCaptureTimestamp() {
        return getOriginalCaptureTimestamp() > 0L;
    }

    public boolean hasImportTimestamp() {
        return getImportTimestamp() > 0L;
    }

    public boolean hasPhotoLocation() {
        return DistanceCalculator.isValidCoordinate(getPhotoLatitude(), getPhotoLongitude());
    }
}
