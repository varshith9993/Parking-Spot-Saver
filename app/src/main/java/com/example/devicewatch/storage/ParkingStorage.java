package com.example.devicewatch.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingHistoryEntry;
import com.example.devicewatch.model.ParkingRecord;
import com.example.devicewatch.model.ParkingSpot;
import com.example.devicewatch.sensor.ShakeDetector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unified persistence coordinator:
 * - Active parking spot pointer & fast snapshot in SharedPreferences (`parking_spot_prefs`)
 * - Structured multi-record Saved Locations & History in SQLite (`ParkingDatabaseHelper`)
 * - User preferences (Shake to Save & Theme) in SharedPreferences (`parking_settings_prefs`)
 * Automatically migrates prior SharedPreferences history/active spots on first run.
 */
public class ParkingStorage {

    private static final String PREFS_NAME = "parking_spot_prefs";
    private static final String LEGACY_HISTORY_PREFS = "parking_history_prefs";
    private static final String SETTINGS_PREFS_NAME = "parking_settings_prefs";

    private static final String KEY_HAS_SPOT = "has_spot";
    private static final String KEY_ACTIVE_RECORD_ID = "active_record_id";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_TIMESTAMP = "timestamp_millis";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_NOTE = "note";
    private static final String KEY_PHOTO_URI = "photo_uri";
    private static final String KEY_PHOTO_CAPTURED_AT = "photo_captured_at_millis";
    private static final String KEY_PHOTO_IMPORTED_AT = "photo_imported_at_millis";
    private static final String KEY_PHOTO_LATITUDE = "photo_latitude";
    private static final String KEY_PHOTO_LONGITUDE = "photo_longitude";

    private static final String KEY_LEGACY_HISTORY_JSON = "history_json";
    private static final String KEY_MIGRATION_V2_DONE = "sqlite_migration_v2_done";

    private static final String KEY_SHAKE_ENABLED = "shake_to_save_enabled";
    private static final String KEY_SHAKE_SENSITIVITY = "shake_to_save_sensitivity";
    private static final String KEY_THEME_MODE = "appearance_theme_mode";

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
            // 1. Migrate legacy JSON history into both history_records and saved_records.
            String rawHistory = legacyHistoryPrefs.getString(KEY_LEGACY_HISTORY_JSON, null);
            if (rawHistory != null && !rawHistory.trim().isEmpty()) {
                try {
                    JSONArray array = new JSONArray(rawHistory);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.optJSONObject(i);
                        ParkingHistoryEntry legacy = ParkingHistoryEntry.fromJson(obj);
                        if (legacy != null) {
                            ParkingRecord record = ParkingRecord.fromParkingSpot(legacy.getId(), legacy.getSpot());
                            db.insertIgnore(ParkingDatabaseHelper.TABLE_HISTORY, record);
                            db.insertIgnore(ParkingDatabaseHelper.TABLE_SAVED, record);
                        }
                    }
                } catch (JSONException ignored) {
                }
            }

            // 2. Ensure existing active spot also has a record ID in saved_records.
            if (hasSavedParkingSpot()) {
                String activeId = prefs.getString(KEY_ACTIVE_RECORD_ID, null);
                if (activeId == null || activeId.isEmpty()) {
                    activeId = UUID.randomUUID().toString();
                    prefs.edit().putString(KEY_ACTIVE_RECORD_ID, activeId).apply();
                }
                ParkingRecord active = readActiveRecordInternal(activeId);
                if (active != null) {
                    db.insertIgnore(ParkingDatabaseHelper.TABLE_SAVED, active);
                }
            }

            settingsPrefs.edit().putBoolean(KEY_MIGRATION_V2_DONE, true).apply();
        }
    }

    public void saveNewActiveRecord(@NonNull ParkingRecord newRecord) {
        ParkingRecord previous = getActiveRecord();
        if (previous != null) {
            db.upsertRecord(ParkingDatabaseHelper.TABLE_HISTORY, previous);
        }
        db.upsertRecord(ParkingDatabaseHelper.TABLE_SAVED, newRecord);
        writeActiveRecordToPrefs(newRecord);
    }

    public void setExistingRecordAsActive(@NonNull ParkingRecord record) {
        ParkingRecord previous = getActiveRecord();
        if (previous != null && !previous.getId().equals(record.getId())) {
            db.upsertRecord(ParkingDatabaseHelper.TABLE_HISTORY, previous);
        }
        db.upsertRecord(ParkingDatabaseHelper.TABLE_SAVED, record);
        writeActiveRecordToPrefs(record);
    }

    public void saveParkingSpot(@NonNull ParkingSpot spot) {
        String id = prefs.getString(KEY_ACTIVE_RECORD_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        ParkingRecord record = ParkingRecord.fromParkingSpot(id, spot);
        db.upsertRecord(ParkingDatabaseHelper.TABLE_SAVED, record);
        writeActiveRecordToPrefs(record);
    }

    private void writeActiveRecordToPrefs(@NonNull ParkingRecord record) {
        prefs.edit()
                .putBoolean(KEY_HAS_SPOT, true)
                .putString(KEY_ACTIVE_RECORD_ID, record.getId())
                .putLong(KEY_LATITUDE, Double.doubleToRawLongBits(record.getLatitude()))
                .putLong(KEY_LONGITUDE, Double.doubleToRawLongBits(record.getLongitude()))
                .putLong(KEY_TIMESTAMP, record.getSavedTimestamp())
                .putString(KEY_ADDRESS, record.getAddress())
                .putString(KEY_NOTE, record.getNote())
                .putString(KEY_PHOTO_URI, record.getPhotoPath())
                .putLong(KEY_PHOTO_CAPTURED_AT, record.getOriginalCaptureTimestamp())
                .putLong(KEY_PHOTO_IMPORTED_AT, record.getImportTimestamp())
                .putLong(KEY_PHOTO_LATITUDE, Double.doubleToRawLongBits(record.getPhotoLatitude()))
                .putLong(KEY_PHOTO_LONGITUDE, Double.doubleToRawLongBits(record.getPhotoLongitude()))
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
        return readActiveRecordInternal(activeId);
    }

    @Nullable
    private ParkingRecord readActiveRecordInternal(@NonNull String recordId) {
        double latitude = Double.longBitsToDouble(prefs.getLong(KEY_LATITUDE, 0L));
        double longitude = Double.longBitsToDouble(prefs.getLong(KEY_LONGITUDE, 0L));
        if (!DistanceCalculator.isValidCoordinate(latitude, longitude)) {
            return null;
        }
        long timestamp = prefs.getLong(KEY_TIMESTAMP, 0L);
        String address = prefs.getString(KEY_ADDRESS, null);
        String note = prefs.getString(KEY_NOTE, null);
        String photoUri = prefs.getString(KEY_PHOTO_URI, null);
        long photoCapturedAt = prefs.getLong(KEY_PHOTO_CAPTURED_AT, 0L);
        long photoImportedAt = prefs.getLong(KEY_PHOTO_IMPORTED_AT, 0L);
        double photoLat = prefs.contains(KEY_PHOTO_LATITUDE)
                ? Double.longBitsToDouble(prefs.getLong(KEY_PHOTO_LATITUDE, Double.doubleToRawLongBits(Double.NaN)))
                : Double.NaN;
        double photoLng = prefs.contains(KEY_PHOTO_LONGITUDE)
                ? Double.longBitsToDouble(prefs.getLong(KEY_PHOTO_LONGITUDE, Double.doubleToRawLongBits(Double.NaN)))
                : Double.NaN;

        return new ParkingRecord(
                recordId,
                latitude,
                longitude,
                address,
                timestamp,
                note,
                photoUri,
                photoCapturedAt,
                photoLat,
                photoLng,
                photoImportedAt
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
        // Never modify KEY_TIMESTAMP or saved_timestamp when updating secondary fields.
        db.updateAddress(ParkingDatabaseHelper.TABLE_SAVED, recordId, address);
        db.updateAddress(ParkingDatabaseHelper.TABLE_HISTORY, recordId, address);
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
        db.updateNote(ParkingDatabaseHelper.TABLE_SAVED, recordId, note);
        db.updateNote(ParkingDatabaseHelper.TABLE_HISTORY, recordId, note);
    }

    public void updateParkingPhoto(@Nullable String photoUri) {
        if (photoUri == null) {
            updateParkingPhotoWithMetadata(null, 0L, 0L, Double.NaN, Double.NaN);
        } else {
            updateParkingPhotoWithMetadata(photoUri, 0L, System.currentTimeMillis(), Double.NaN, Double.NaN);
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
        updateRecordPhotoWithMetadata(
                active.getId(),
                photoUri,
                photoCapturedAtMillis,
                photoImportedAtMillis,
                photoLatitude,
                photoLongitude
        );
    }

    public void updateRecordPhotoWithMetadata(
            @NonNull String recordId,
            @Nullable String photoPath,
            long originalCaptureTimestamp,
            long importTimestamp,
            double photoLatitude,
            double photoLongitude
    ) {
        ParkingRecord active = getActiveRecord();
        if (active != null && active.getId().equals(recordId)) {
            prefs.edit()
                    .putString(KEY_PHOTO_URI, photoPath)
                    .putLong(KEY_PHOTO_CAPTURED_AT, originalCaptureTimestamp)
                    .putLong(KEY_PHOTO_IMPORTED_AT, importTimestamp)
                    .putLong(KEY_PHOTO_LATITUDE, Double.doubleToRawLongBits(photoLatitude))
                    .putLong(KEY_PHOTO_LONGITUDE, Double.doubleToRawLongBits(photoLongitude))
                    .apply();
        }
        db.updatePhotoMetadata(
                ParkingDatabaseHelper.TABLE_SAVED,
                recordId,
                photoPath,
                originalCaptureTimestamp,
                photoLatitude,
                photoLongitude,
                importTimestamp
        );
        db.updatePhotoMetadata(
                ParkingDatabaseHelper.TABLE_HISTORY,
                recordId,
                photoPath,
                originalCaptureTimestamp,
                photoLatitude,
                photoLongitude,
                importTimestamp
        );
    }

    public void clearActiveSpot() {
        prefs.edit().clear().apply();
    }

    public void clearParkingSpot() {
        clearActiveSpot();
    }

    @NonNull
    public List<ParkingRecord> getSavedRecords() {
        return db.getAllRecords(ParkingDatabaseHelper.TABLE_SAVED);
    }

    @Nullable
    public ParkingRecord getSavedRecordById(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return db.getRecordById(ParkingDatabaseHelper.TABLE_SAVED, id);
    }

    public int getSavedRecordsCount() {
        return db.getCount(ParkingDatabaseHelper.TABLE_SAVED);
    }

    @Nullable
    public ParkingRecord deleteSavedRecord(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ParkingRecord active = getActiveRecord();
        if (active != null && active.getId().equals(id)) {
            clearActiveSpot();
        }
        return db.deleteById(ParkingDatabaseHelper.TABLE_SAVED, id);
    }

    @NonNull
    public List<ParkingRecord> getHistoryRecords() {
        return db.getAllRecords(ParkingDatabaseHelper.TABLE_HISTORY);
    }

    @Nullable
    public ParkingRecord getHistoryRecordById(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return db.getRecordById(ParkingDatabaseHelper.TABLE_HISTORY, id);
    }

    public int getHistoryRecordsCount() {
        return db.getCount(ParkingDatabaseHelper.TABLE_HISTORY);
    }

    @Nullable
    public ParkingRecord deleteHistoryRecord(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return db.deleteById(ParkingDatabaseHelper.TABLE_HISTORY, id);
    }

    @NonNull
    public List<String> clearParkingHistory() {
        legacyHistoryPrefs.edit().clear().apply();
        return db.clearTableAndCollectPhotos(ParkingDatabaseHelper.TABLE_HISTORY);
    }

    public void clearAllParkingData() {
        clearActiveSpot();
        legacyHistoryPrefs.edit().clear().apply();
        db.clearTableAndCollectPhotos(ParkingDatabaseHelper.TABLE_SAVED);
        db.clearTableAndCollectPhotos(ParkingDatabaseHelper.TABLE_HISTORY);
    }

    public boolean isPhotoReferenced(@Nullable String photoPath) {
        if (photoPath == null || photoPath.trim().isEmpty()) {
            return false;
        }
        ParkingRecord active = getActiveRecord();
        if (active != null && photoPath.equals(active.getPhotoPath())) {
            return true;
        }
        return db.isPhotoReferencedInTable(ParkingDatabaseHelper.TABLE_SAVED, photoPath)
                || db.isPhotoReferencedInTable(ParkingDatabaseHelper.TABLE_HISTORY, photoPath);
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
}
