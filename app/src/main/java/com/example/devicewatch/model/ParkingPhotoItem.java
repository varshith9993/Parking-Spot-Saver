package com.example.devicewatch.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single persistent parking photo along with its EXIF capture/import metadata.
 */
public class ParkingPhotoItem {

    public static final int MAX_PHOTOS_PER_RECORD = 5;

    private static final String KEY_PATH = "filePath";
    private static final String KEY_CAPTURE_TS = "originalCaptureTimestamp";
    private static final String KEY_LAT = "photoLatitude";
    private static final String KEY_LNG = "photoLongitude";
    private static final String KEY_IMPORT_TS = "importTimestamp";

    @NonNull
    private final String filePath;
    private final long originalCaptureTimestamp;
    private final double photoLatitude;
    private final double photoLongitude;
    private final long importTimestamp;

    public ParkingPhotoItem(
            @NonNull String filePath,
            long originalCaptureTimestamp,
            double photoLatitude,
            double photoLongitude,
            long importTimestamp
    ) {
        this.filePath = filePath;
        this.originalCaptureTimestamp = originalCaptureTimestamp;
        this.photoLatitude = photoLatitude;
        this.photoLongitude = photoLongitude;
        this.importTimestamp = importTimestamp;
    }

    @NonNull
    public String getFilePath() {
        return filePath;
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

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(KEY_PATH, filePath);
        obj.put(KEY_CAPTURE_TS, originalCaptureTimestamp);
        if (hasPhotoLocation()) {
            obj.put(KEY_LAT, photoLatitude);
            obj.put(KEY_LNG, photoLongitude);
        }
        obj.put(KEY_IMPORT_TS, importTimestamp);
        return obj;
    }

    @Nullable
    public static ParkingPhotoItem fromJson(@Nullable JSONObject obj) {
        if (obj == null) {
            return null;
        }
        String path = obj.optString(KEY_PATH, "");
        if (path.trim().isEmpty()) {
            return null;
        }
        long captureTs = obj.optLong(KEY_CAPTURE_TS, 0L);
        long importTs = obj.optLong(KEY_IMPORT_TS, 0L);
        double lat = Double.NaN;
        double lng = Double.NaN;
        if (!obj.isNull(KEY_LAT) && !obj.isNull(KEY_LNG)) {
            double cLat = obj.optDouble(KEY_LAT, Double.NaN);
            double cLng = obj.optDouble(KEY_LNG, Double.NaN);
            if (DistanceCalculator.isValidCoordinate(cLat, cLng)) {
                lat = cLat;
                lng = cLng;
            }
        }
        return new ParkingPhotoItem(path, captureTs, lat, lng, importTs);
    }

    @Nullable
    public static String toJsonString(@Nullable List<ParkingPhotoItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        JSONArray array = new JSONArray();
        int limit = Math.min(items.size(), MAX_PHOTOS_PER_RECORD);
        for (int i = 0; i < limit; i++) {
            try {
                array.put(items.get(i).toJson());
            } catch (JSONException ignored) {
            }
        }
        return array.length() == 0 ? null : array.toString();
    }

    @NonNull
    public static List<ParkingPhotoItem> fromJsonString(@Nullable String raw) {
        List<ParkingPhotoItem> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && list.size() < MAX_PHOTOS_PER_RECORD; i++) {
                ParkingPhotoItem item = fromJson(array.optJSONObject(i));
                if (item != null) {
                    list.add(item);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }
}
