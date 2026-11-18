package com.example.devicewatch.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Immutable historical parking record preserving a previous ParkingSpot snapshot.
 */
public class ParkingHistoryEntry {

    private static final String KEY_ID = "id";
    private static final String KEY_LAT = "latitude";
    private static final String KEY_LNG = "longitude";
    private static final String KEY_TIMESTAMP = "timestampMillis";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_NOTE = "note";
    private static final String KEY_PHOTO_URI = "photoUri";
    private static final String KEY_PHOTO_CAPTURED_AT = "photoCapturedAtMillis";
    private static final String KEY_PHOTO_IMPORTED_AT = "photoImportedAtMillis";
    private static final String KEY_PHOTO_LAT = "photoLatitude";
    private static final String KEY_PHOTO_LNG = "photoLongitude";

    @NonNull
    private final String id;
    @NonNull
    private final ParkingSpot spot;

    public ParkingHistoryEntry(@NonNull String id, @NonNull ParkingSpot spot) {
        this.id = id;
        this.spot = spot;
    }

    @NonNull
    public static ParkingHistoryEntry fromSpot(@NonNull ParkingSpot spot) {
        return new ParkingHistoryEntry(UUID.randomUUID().toString(), spot);
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public ParkingSpot getSpot() {
        return spot;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(KEY_ID, id);
        obj.put(KEY_LAT, spot.getLatitude());
        obj.put(KEY_LNG, spot.getLongitude());
        obj.put(KEY_TIMESTAMP, spot.getTimestampMillis());
        if (spot.getAddress() != null) {
            obj.put(KEY_ADDRESS, spot.getAddress());
        }
        if (spot.getNote() != null) {
            obj.put(KEY_NOTE, spot.getNote());
        }
        if (spot.getPhotoUri() != null) {
            obj.put(KEY_PHOTO_URI, spot.getPhotoUri());
        }
        obj.put(KEY_PHOTO_CAPTURED_AT, spot.getPhotoCapturedAtMillis());
        obj.put(KEY_PHOTO_IMPORTED_AT, spot.getPhotoImportedAtMillis());
        if (spot.hasPhotoLocation()) {
            obj.put(KEY_PHOTO_LAT, spot.getPhotoLatitude());
            obj.put(KEY_PHOTO_LNG, spot.getPhotoLongitude());
        }
        return obj;
    }

    @Nullable
    public static ParkingHistoryEntry fromJson(@Nullable JSONObject obj) {
        if (obj == null) {
            return null;
        }
        try {
            String id = obj.optString(KEY_ID, "");
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString();
            }
            double lat = obj.getDouble(KEY_LAT);
            double lng = obj.getDouble(KEY_LNG);
            if (!DistanceCalculator.isValidCoordinate(lat, lng)) {
                return null;
            }
            long timestamp = obj.optLong(KEY_TIMESTAMP, 0L);
            String address = obj.isNull(KEY_ADDRESS) ? null : obj.optString(KEY_ADDRESS, null);
            String note = obj.isNull(KEY_NOTE) ? null : obj.optString(KEY_NOTE, null);
            String photoUri = obj.isNull(KEY_PHOTO_URI) ? null : obj.optString(KEY_PHOTO_URI, null);
            long photoCapturedAt = obj.optLong(KEY_PHOTO_CAPTURED_AT, 0L);
            long photoImportedAt = obj.optLong(KEY_PHOTO_IMPORTED_AT, 0L);

            double photoLat = Double.NaN;
            double photoLng = Double.NaN;
            if (!obj.isNull(KEY_PHOTO_LAT) && !obj.isNull(KEY_PHOTO_LNG)) {
                double candidateLat = obj.optDouble(KEY_PHOTO_LAT, Double.NaN);
                double candidateLng = obj.optDouble(KEY_PHOTO_LNG, Double.NaN);
                if (DistanceCalculator.isValidCoordinate(candidateLat, candidateLng)) {
                    photoLat = candidateLat;
                    photoLng = candidateLng;
                }
            }

            ParkingSpot spot = new ParkingSpot(
                    lat,
                    lng,
                    timestamp,
                    address,
                    note,
                    photoUri,
                    photoCapturedAt,
                    photoImportedAt,
                    photoLat,
                    photoLng
            );
            return new ParkingHistoryEntry(id, spot);
        } catch (JSONException e) {
            return null;
        }
    }
}
