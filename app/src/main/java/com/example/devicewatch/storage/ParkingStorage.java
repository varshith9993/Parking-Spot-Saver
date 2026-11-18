package com.example.devicewatch.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingHistoryEntry;
import com.example.devicewatch.model.ParkingPhotoItem;
import com.example.devicewatch.model.ParkingRecord;
import com.example.devicewatch.model.ParkingSpot;
import com.example.devicewatch.navigation.NavigationManager;
import com.example.devicewatch.sensor.ShakeDetector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Unified persistence coordinator:
 * - Active parking spot pointer & fast snapshot in SharedPreferences (`parking_spot_prefs`)
 * - Structured multi-photo parking records with `isSaved` bookmark flag in SQLite (`ParkingDatabaseHelper`)
 * - User preferences (Shake to Save, Theme, Navigation mode) in SharedPreferences (`parking_settings_prefs`)
 */
public class ParkingStorage {

    private static final String PREFS_NAME = "parking_spot_prefs";
    private static final String LEGACY_HISTORY_PREFS = "parking_history_prefs";
    private static final String SETTINGS_PREFS_NAME = "parking_settings_prefs";

    private static final String KEY_HAS_SPOT = "has_spot";
    private static final String KEY_ACTIVE_RECORD_ID = "active_record_id";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_PLACE_LABEL = "place_label";
    private static final String KEY_TIMESTAMP = "timestamp_millis";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_NOTE = "note";
    private static final String KEY_PHOTO_URI = "photo_uri";
    private static final String KEY_PHOTO_CAPTURED_AT = "photo_captured_at_millis";
    private static final String KEY_PHOTO_IMPORTED_AT = "photo_imported_at_millis";
    private static final String KEY_PHOTO_LATITUDE = "photo_latitude";
    private static final String KEY_PHOTO_LONGITUDE = "photo_longitude";
    private static final String KEY_PHOTOS_JSON = "photos_json";
    private static final String KEY_IS_SAVED = "is_saved";

    private static final String KEY_LEGACY_HISTORY_JSON = "history_json";
    private static final String KEY_MIGRATION_V2_DONE = "sqlite_migration_v2_done";

    private static final String KEY_SHAKE_ENABLED = "shake_to_save_enabled";
    private static final String KEY_SHAKE_SENSITIVITY = "shake_to_save_sensitivity";
    private static final String KEY_THEME_MODE = "appearance_theme_mode";
    private static final String KEY_NAV_TRAVEL_MODE = "nav_travel_mode";

    private static final Object MIGRATION_LOCK = new Object();
    private static volatile ParkingDatabaseHelper sharedDbHelper;

    private final SharedPreferences prefs;
    private final SharedPreferences legacyHistoryPrefs;
    private final SharedPreferences settingsPrefs;
    private final ParkingDatabaseHelper db;

    public ParkingStorage(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.legacyHistoryPrefs = appContext.getSharedPreferences(LEGACY_HISTORY_PREFS, Context.MODE_PRIVATE);
        this.settingsPrefs = appContext.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        this.db = getDbHelper(appContext);
        migrateLegacyDataIfNeeded();
    }

    @NonNull
    private static ParkingDatabaseHelper getDbHelper(@NonNull Context appContext) {
        if (sharedDbHelper == null) {
            synchronized (MIGRATION_LOCK) {
                if (sharedDbHelper == null) {
                    sharedDbHelper = new ParkingDatabaseHelper(appContext);
                }
            }
        }
        return sharedDbHelper;
    }

    private void migrateLegacyDataIfNeeded() {
        if (settingsPrefs.getBoolean(KEY_MIGRATION_V2_DONE, false)) {
            return;
        }
        synchronized (MIGRATION_LOCK) {
            if (settingsPrefs.getBoolean(KEY_MIGRATION_V2_DONE, false)) {
                return;
            }
            String rawHistory = legacyHistoryPrefs.getString(KEY_LEGACY_HISTORY_JSON, null);
            if (rawHistory != null && !rawHistory.trim().isEmpty()) {
                try {
                    JSONArray array = new JSONArray(rawHistory);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.optJSONObject(i);
                        ParkingHistoryEntry legacy = ParkingHistoryEntry.fromJson(obj);
                        if (legacy != null) {
                            ParkingRecord record = ParkingRecord.fromParkingSpot(legacy.getId(), legacy.getSpot());
                            db.insertIgnore(record, true);
                        }
                    }
                } catch (JSONException ignored) {
                }
            }

            if (hasSavedParkingSpot()) {
                String activeId = prefs.getString(KEY_ACTIVE_RECORD_ID, null);
                if (activeId == null || activeId.isEmpty()) {
                    activeId = UUID.randomUUID().toString();
                    prefs.edit().putString(KEY_ACTIVE_RECORD_ID, activeId).apply();
                }
                ParkingRecord active = readActiveRecordInternal(activeId);
                if (active != null) {
                    db.insertIgnore(active, true);
                }
            }

            settingsPrefs.edit().putBoolean(KEY_MIGRATION_V2_DONE, true).apply();
        }
    }

    public void saveNewActiveRecord(@NonNull ParkingRecord newRecord) {
        db.upsertRecord(newRecord, true);
        writeActiveRecordToPrefs(newRecord);
    }

    public void setExistingRecordAsActive(@NonNull ParkingRecord record) {
        db.upsertRecord(record, true);
        writeActiveRecordToPrefs(record);
    }

    public void saveParkingSpot(@NonNull ParkingSpot spot) {
        String id = prefs.getString(KEY_ACTIVE_RECORD_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        ParkingRecord existing = db.getRecordById(id);
        ParkingRecord record = new ParkingRecord(
                id,
                spot.getLatitude(),
                spot.getLongitude(),
                existing != null ? existing.getPlaceLabel() : null,
                spot.getAddress(),
                spot.getTimestampMillis(),
                spot.getNote(),
                spot.getPhotoUri(),
                spot.getPhotoCapturedAtMillis(),
                spot.getPhotoLatitude(),
                spot.getPhotoLongitude(),
                spot.getPhotoImportedAtMillis(),
                existing != null && existing.isSaved()
        );
        db.upsertRecord(record, true);
        writeActiveRecordToPrefs(record);
    }

    private void writeActiveRecordToPrefs(@NonNull ParkingRecord record) {
        List<ParkingPhotoItem> photos = record.getPhotos();
        prefs.edit()
                .putBoolean(KEY_HAS_SPOT, true)
                .putString(KEY_ACTIVE_RECORD_ID, record.getId())
                .putLong(KEY_LATITUDE, Double.doubleToRawLongBits(record.getLatitude()))
                .putLong(KEY_LONGITUDE, Double.doubleToRawLongBits(record.getLongitude()))
                .putString(KEY_PLACE_LABEL, record.getPlaceLabel())
                .putLong(KEY_TIMESTAMP, record.getSavedTimestamp())
                .putString(KEY_ADDRESS, record.getAddress())
                .putString(KEY_NOTE, record.getNote())
                .putString(KEY_PHOTO_URI, record.getPhotoPath())
                .putLong(KEY_PHOTO_CAPTURED_AT, record.getOriginalCaptureTimestamp())
                .putLong(KEY_PHOTO_IMPORTED_AT, record.getImportTimestamp())
                .putLong(KEY_PHOTO_LATITUDE, Double.doubleToRawLongBits(record.getPhotoLatitude()))
                .putLong(KEY_PHOTO_LONGITUDE, Double.doubleToRawLongBits(record.getPhotoLongitude()))
                .putString(KEY_PHOTOS_JSON, ParkingPhotoItem.toJsonString(photos))
                .putBoolean(KEY_IS_SAVED, record.isSaved())
                .apply();
    }

    @Nullable
    public ParkingRecord getActiveRecord() {
        if (!hasSavedParkingSpot()) {
            return null;
        }
        String activeId = prefs.getString(KEY_ACTIVE_RECORD_ID, null);
        if (activeId == null || activeId.isEmpty()) {
            activeId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_ACTIVE_RECORD_ID, activeId).apply();
        }
        ParkingRecord fromDb = db.getRecordById(activeId);
        if (fromDb != null) {
            return fromDb;
        }
        return readActiveRecordInternal(activeId);
    }

    @Nullable
    private ParkingRecord readActiveRecordInternal(@NonNull String recordId) {
        double latitude = Double.longBitsToDouble(prefs.getLong(KEY_LATITUDE, 0L));
        double longitude = Double.longBitsToDouble(prefs.getLong(KEY_LONGITUDE, 0L));
        if (!DistanceCalculator.isValidCoordinate(latitude, longitude)) {
            return null;
        }
        String placeLabel = prefs.getString(KEY_PLACE_LABEL, null);
        long timestamp = prefs.getLong(KEY_TIMESTAMP, 0L);
        String address = prefs.getString(KEY_ADDRESS, null);
        String note = prefs.getString(KEY_NOTE, null);
        List<ParkingPhotoItem> photos = ParkingPhotoItem.fromJsonString(prefs.getString(KEY_PHOTOS_JSON, null));
        if (photos.isEmpty()) {
            String photoUri = prefs.getString(KEY_PHOTO_URI, null);
            if (photoUri != null && !photoUri.trim().isEmpty()) {
                long photoCapturedAt = prefs.getLong(KEY_PHOTO_CAPTURED_AT, 0L);
                long photoImportedAt = prefs.getLong(KEY_PHOTO_IMPORTED_AT, 0L);
                double photoLat = prefs.contains(KEY_PHOTO_LATITUDE)
                        ? Double.longBitsToDouble(prefs.getLong(KEY_PHOTO_LATITUDE, Double.doubleToRawLongBits(Double.NaN)))
                        : Double.NaN;
                double photoLng = prefs.contains(KEY_PHOTO_LONGITUDE)
                        ? Double.longBitsToDouble(prefs.getLong(KEY_PHOTO_LONGITUDE, Double.doubleToRawLongBits(Double.NaN)))
                        : Double.NaN;
                photos = Collections.singletonList(
                        new ParkingPhotoItem(photoUri, photoCapturedAt, photoLat, photoLng, photoImportedAt)
                );
            }
        }
        boolean isSaved = prefs.getBoolean(KEY_IS_SAVED, false);

        return new ParkingRecord(
                recordId,
                latitude,
                longitude,
                placeLabel,
                address,
                timestamp,
                note,
                photos,
                isSaved
        );
    }

    @Nullable
    public ParkingSpot getParkingSpot() {
        ParkingRecord active = getActiveRecord();
        return active != null ? active.toParkingSpot() : null;
    }

    public boolean hasSavedParkingSpot() {
        return prefs.getBoolean(KEY_HAS_SPOT, false)
                && prefs.contains(KEY_LATITUDE)
                && prefs.contains(KEY_LONGITUDE);
    }

    public void setRecordSaved(@NonNull String recordId, boolean isSaved) {
        ParkingRecord active = getActiveRecord();
        if (active != null && active.getId().equals(recordId)) {
            prefs.edit().putBoolean(KEY_IS_SAVED, isSaved).apply();
        }
        db.setSaved(recordId, isSaved);
    }

    public void updateRecordPlaceLabel(@NonNull String recordId, @Nullable String placeLabel) {
        String normalized = placeLabel != null && !placeLabel.trim().isEmpty()
                ? placeLabel.trim()
                : null;
        ParkingRecord active = getActiveRecord();
        if (active != null && active.getId().equals(recordId)) {
            prefs.edit().putString(KEY_PLACE_LABEL, normalized).apply();
        }
        db.updatePlaceLabel(recordId, normalized);
    }

    public void updateActiveAddress(@Nullable String address) {
        ParkingRecord active = getActiveRecord();
        if (active == null) {
            return;
        }
        updateRecordAddress(active.getId(), address);
    }

    public void updateRecordAddress(@NonNull String recordId, @Nullable String address) {
        ParkingRecord active = getActiveRecord();
        if (active != null && active.getId().equals(recordId)) {
            prefs.edit().putString(KEY_ADDRESS, address).apply();
        }
        db.updateAddress(recordId, address);
    }

    public void updateParkingAddress(@Nullable String address) {
        updateActiveAddress(address);
    }

    public void updateActiveNote(@Nullable String note) {
        ParkingRecord active = getActiveRecord();
        if (active == null) {
            return;
        }
        updateRecordNote(active.getId(), note);
    }

    public void updateParkingNote(@Nullable String note) {
        updateActiveNote(note);
    }

    public void updateRecordNote(@NonNull String recordId, @Nullable String note) {
        ParkingRecord active = getActiveRecord();
        if (active != null && active.getId().equals(recordId)) {
            prefs.edit().putString(KEY_NOTE, note).apply();
        }
        db.updateNote(recordId, note);
    }

    public void updateRecordPhotos(@NonNull String recordId, @NonNull List<ParkingPhotoItem> photos) {
        List<ParkingPhotoItem> capped = photos.size() > ParkingPhotoItem.MAX_PHOTOS_PER_RECORD
                ? new ArrayList<>(photos.subList(0, ParkingPhotoItem.MAX_PHOTOS_PER_RECORD))
                : photos;

        ParkingRecord active = getActiveRecord();
        if (active != null && active.getId().equals(recordId)) {
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY_PHOTOS_JSON, ParkingPhotoItem.toJsonString(capped));
            if (capped.isEmpty()) {
                editor.putString(KEY_PHOTO_URI, null)
                        .putLong(KEY_PHOTO_CAPTURED_AT, 0L)
                        .putLong(KEY_PHOTO_IMPORTED_AT, 0L)
                        .putLong(KEY_PHOTO_LATITUDE, Double.doubleToRawLongBits(Double.NaN))
                        .putLong(KEY_PHOTO_LONGITUDE, Double.doubleToRawLongBits(Double.NaN));
            } else {
                ParkingPhotoItem first = capped.get(0);
                editor.putString(KEY_PHOTO_URI, first.getFilePath())
                        .putLong(KEY_PHOTO_CAPTURED_AT, first.getOriginalCaptureTimestamp())
                        .putLong(KEY_PHOTO_IMPORTED_AT, first.getImportTimestamp())
                        .putLong(KEY_PHOTO_LATITUDE, Double.doubleToRawLongBits(first.getPhotoLatitude()))
                        .putLong(KEY_PHOTO_LONGITUDE, Double.doubleToRawLongBits(first.getPhotoLongitude()));
            }
            editor.apply();
        }
        db.updatePhotos(recordId, capped);
    }

    public boolean addPhotoToRecord(@NonNull String recordId, @NonNull ParkingPhotoItem item) {
        ParkingRecord record = getRecordById(recordId);
        if (record == null || record.getPhotoCount() >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
            return false;
        }
        List<ParkingPhotoItem> updated = new ArrayList<>(record.getPhotos());
        updated.add(item);
        updateRecordPhotos(recordId, updated);
        return true;
    }

    @Nullable
    public ParkingPhotoItem replacePhotoInRecord(
            @NonNull String recordId,
            int index,
            @NonNull ParkingPhotoItem replacement
    ) {
        ParkingRecord record = getRecordById(recordId);
        if (record == null || index < 0 || index >= record.getPhotoCount()) {
            return null;
        }
        List<ParkingPhotoItem> updated = new ArrayList<>(record.getPhotos());
        ParkingPhotoItem old = updated.set(index, replacement);
        updateRecordPhotos(recordId, updated);
        return old;
    }

    @Nullable
    public ParkingPhotoItem removePhotoFromRecord(@NonNull String recordId, int index) {
        ParkingRecord record = getRecordById(recordId);
        if (record == null || index < 0 || index >= record.getPhotoCount()) {
            return null;
        }
        List<ParkingPhotoItem> updated = new ArrayList<>(record.getPhotos());
        ParkingPhotoItem removed = updated.remove(index);
        updateRecordPhotos(recordId, updated);
        return removed;
    }

    public void updateParkingPhoto(@Nullable String photoUri) {
        ParkingRecord active = getActiveRecord();
        if (active == null) {
            return;
        }
        if (photoUri == null) {
            updateRecordPhotos(active.getId(), Collections.emptyList());
        } else {
            updateRecordPhotos(
                    active.getId(),
                    Collections.singletonList(
                            new ParkingPhotoItem(photoUri, 0L, Double.NaN, Double.NaN, System.currentTimeMillis())
                    )
            );
        }
    }

    public void updateParkingPhotoWithMetadata(
            @Nullable String photoUri,
            long photoCapturedAtMillis,
            long photoImportedAtMillis,
            double photoLatitude,
            double photoLongitude
    ) {
        ParkingRecord active = getActiveRecord();
        if (active == null) {
            return;
        }
        if (photoUri == null) {
            updateRecordPhotos(active.getId(), Collections.emptyList());
        } else {
            updateRecordPhotos(
                    active.getId(),
                    Collections.singletonList(
                            new ParkingPhotoItem(
                                    photoUri,
                                    photoCapturedAtMillis,
                                    photoLatitude,
                                    photoLongitude,
                                    photoImportedAtMillis
                            )
                    )
            );
        }
    }

    public void clearActiveSpot() {
        prefs.edit().clear().apply();
    }

    public void clearParkingSpot() {
        clearActiveSpot();
    }

    @NonNull
    public List<ParkingRecord> getSavedRecords() {
        return db.getSavedRecords();
    }

    @Nullable
    public ParkingRecord getSavedRecordById(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ParkingRecord record = db.getRecordById(id);
        return (record != null && record.isSaved()) ? record : null;
    }

    public int getSavedRecordsCount() {
        return db.getSavedCount();
    }

    public void unsaveRecord(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        setRecordSaved(id, false);
    }

    @NonNull
    public List<ParkingRecord> getHistoryRecords() {
        return db.getHistoryRecords();
    }

    @Nullable
    public ParkingRecord getRecordById(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return db.getRecordById(id);
    }

    @Nullable
    public ParkingRecord getHistoryRecordById(@Nullable String id) {
        return getRecordById(id);
    }

    public int getHistoryRecordsCount() {
        return db.getHistoryCount();
    }

    @Nullable
    public ParkingRecord deleteHistoryRecord(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ParkingRecord active = getActiveRecord();
        ParkingRecord removed = db.removeFromHistory(id);
        if (removed != null && !removed.isSaved() && active != null && active.getId().equals(id)) {
            clearActiveSpot();
        }
        return removed;
    }

    @NonNull
    public List<String> clearParkingHistory() {
        legacyHistoryPrefs.edit().clear().apply();
        List<String> deletedPhotos = db.clearHistoryPreservingSaved();
        ParkingRecord active = getActiveRecord();
        if (active != null && !active.isSaved() && db.getRecordById(active.getId()) == null) {
            clearActiveSpot();
        }
        return deletedPhotos;
    }

    public void clearAllParkingData() {
        clearActiveSpot();
        legacyHistoryPrefs.edit().clear().apply();
        db.deleteAllRecords();
    }

    public boolean isPhotoReferenced(@Nullable String photoPath) {
        if (photoPath == null || photoPath.trim().isEmpty()) {
            return false;
        }
        ParkingRecord active = getActiveRecord();
        if (active != null) {
            for (ParkingPhotoItem item : active.getPhotos()) {
                if (photoPath.equals(item.getFilePath())) {
                    return true;
                }
            }
        }
        return db.isPhotoReferenced(photoPath);
    }

    public boolean isShakeToSaveEnabled() {
        return settingsPrefs.getBoolean(KEY_SHAKE_ENABLED, true);
    }

    public void setShakeToSaveEnabled(boolean enabled) {
        settingsPrefs.edit().putBoolean(KEY_SHAKE_ENABLED, enabled).apply();
    }

    public int getShakeSensitivity() {
        int value = settingsPrefs.getInt(KEY_SHAKE_SENSITIVITY, ShakeDetector.SENSITIVITY_NORMAL);
        return value == ShakeDetector.SENSITIVITY_HIGH
                ? ShakeDetector.SENSITIVITY_HIGH
                : ShakeDetector.SENSITIVITY_NORMAL;
    }

    public void setShakeSensitivity(int sensitivity) {
        int normalized = sensitivity == ShakeDetector.SENSITIVITY_HIGH
                ? ShakeDetector.SENSITIVITY_HIGH
                : ShakeDetector.SENSITIVITY_NORMAL;
        settingsPrefs.edit().putInt(KEY_SHAKE_SENSITIVITY, normalized).apply();
    }

    public int getThemeMode() {
        int mode = settingsPrefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (mode == AppCompatDelegate.MODE_NIGHT_NO || mode == AppCompatDelegate.MODE_NIGHT_YES) {
            return mode;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    public void setThemeMode(int mode) {
        int normalized = (mode == AppCompatDelegate.MODE_NIGHT_NO || mode == AppCompatDelegate.MODE_NIGHT_YES)
                ? mode
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        settingsPrefs.edit().putInt(KEY_THEME_MODE, normalized).apply();
    }

    @NonNull
    public String getNavigationTravelMode() {
        String mode = settingsPrefs.getString(KEY_NAV_TRAVEL_MODE, NavigationManager.TRAVEL_WALKING);
        return NavigationManager.TRAVEL_DRIVING.equals(mode)
                ? NavigationManager.TRAVEL_DRIVING
                : NavigationManager.TRAVEL_WALKING;
    }

    public void setNavigationTravelMode(@NonNull String mode) {
        String normalized = NavigationManager.TRAVEL_DRIVING.equals(mode)
                ? NavigationManager.TRAVEL_DRIVING
                : NavigationManager.TRAVEL_WALKING;
        settingsPrefs.edit().putString(KEY_NAV_TRAVEL_MODE, normalized).apply();
    }
}
