package com.example.devicewatch.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingPhotoItem;
import com.example.devicewatch.model.ParkingRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local SQLite persistence for parking records using a unified table keyed by stable record IDs.
 * Supports up to 5 photos per record (`photos_json`) while remaining backward-compatible with
 * single-photo legacy records.
 */
public class ParkingDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "parking_spot_saver.db";
    private static final int DB_VERSION = 4;

    public static final String TABLE_RECORDS = "parking_records";
    private static final String LEGACY_TABLE_SAVED = "saved_records";
    private static final String LEGACY_TABLE_HISTORY = "history_records";

    private static final String COL_ID = "id";
    private static final String COL_LAT = "latitude";
    private static final String COL_LNG = "longitude";
    private static final String COL_PLACE_LABEL = "place_label";
    private static final String COL_ADDRESS = "address";
    private static final String COL_SAVED_TS = "saved_timestamp";
    private static final String COL_NOTE = "note";
    private static final String COL_PHOTO_PATH = "photo_path";
    private static final String COL_PHOTO_CAPTURE_TS = "photo_capture_timestamp";
    private static final String COL_PHOTO_LAT = "photo_latitude";
    private static final String COL_PHOTO_LNG = "photo_longitude";
    private static final String COL_PHOTO_IMPORT_TS = "photo_import_timestamp";
    private static final String COL_PHOTOS_JSON = "photos_json";
    private static final String COL_IS_SAVED = "is_saved";
    private static final String COL_IN_HISTORY = "in_history";

    public ParkingDatabaseHelper(@NonNull Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(buildCreateUnifiedTableSql());
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            if (tableExists(db, LEGACY_TABLE_SAVED)) {
                addColumnIfMissing(db, LEGACY_TABLE_SAVED, COL_PLACE_LABEL, "TEXT");
            }
            if (tableExists(db, LEGACY_TABLE_HISTORY)) {
                addColumnIfMissing(db, LEGACY_TABLE_HISTORY, COL_PLACE_LABEL, "TEXT");
            }
        }
        if (oldVersion < 3) {
            db.execSQL(buildCreateUnifiedTableSql());
            if (tableExists(db, LEGACY_TABLE_HISTORY)) {
                copyLegacyTable(db, LEGACY_TABLE_HISTORY);
                db.execSQL("DROP TABLE IF EXISTS " + LEGACY_TABLE_HISTORY);
            }
            if (tableExists(db, LEGACY_TABLE_SAVED)) {
                copyLegacyTable(db, LEGACY_TABLE_SAVED);
                db.execSQL("DROP TABLE IF EXISTS " + LEGACY_TABLE_SAVED);
            }
        }
        if (oldVersion < 4) {
            if (tableExists(db, TABLE_RECORDS)) {
                addColumnIfMissing(db, TABLE_RECORDS, COL_PHOTOS_JSON, "TEXT");
            }
        }
    }

    private static void copyLegacyTable(@NonNull SQLiteDatabase db, @NonNull String sourceTable) {
        db.execSQL(
                "INSERT OR IGNORE INTO " + TABLE_RECORDS + " ("
                        + COL_ID + ", " + COL_LAT + ", " + COL_LNG + ", " + COL_PLACE_LABEL + ", "
                        + COL_ADDRESS + ", " + COL_SAVED_TS + ", " + COL_NOTE + ", " + COL_PHOTO_PATH + ", "
                        + COL_PHOTO_CAPTURE_TS + ", " + COL_PHOTO_LAT + ", " + COL_PHOTO_LNG + ", "
                        + COL_PHOTO_IMPORT_TS + ", " + COL_IS_SAVED + ", " + COL_IN_HISTORY + ") "
                        + "SELECT "
                        + COL_ID + ", " + COL_LAT + ", " + COL_LNG + ", " + COL_PLACE_LABEL + ", "
                        + COL_ADDRESS + ", " + COL_SAVED_TS + ", " + COL_NOTE + ", " + COL_PHOTO_PATH + ", "
                        + COL_PHOTO_CAPTURE_TS + ", " + COL_PHOTO_LAT + ", " + COL_PHOTO_LNG + ", "
                        + COL_PHOTO_IMPORT_TS + ", 0, 1 FROM " + sourceTable
        );
    }

    private static boolean tableExists(@NonNull SQLiteDatabase db, @NonNull String table) {
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{table}
        )) {
            return c.moveToFirst();
        }
    }

    private static void addColumnIfMissing(
            @NonNull SQLiteDatabase db,
            @NonNull String table,
            @NonNull String column,
            @NonNull String sqlType
    ) {
        boolean exists = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (nameIdx >= 0 && column.equals(c.getString(nameIdx))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + sqlType);
        }
    }

    private static String buildCreateUnifiedTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_RECORDS + " ("
                + COL_ID + " TEXT PRIMARY KEY, "
                + COL_LAT + " REAL NOT NULL, "
                + COL_LNG + " REAL NOT NULL, "
                + COL_PLACE_LABEL + " TEXT, "
                + COL_ADDRESS + " TEXT, "
                + COL_SAVED_TS + " INTEGER NOT NULL, "
                + COL_NOTE + " TEXT, "
                + COL_PHOTO_PATH + " TEXT, "
                + COL_PHOTO_CAPTURE_TS + " INTEGER NOT NULL DEFAULT 0, "
                + COL_PHOTO_LAT + " REAL, "
                + COL_PHOTO_LNG + " REAL, "
                + COL_PHOTO_IMPORT_TS + " INTEGER NOT NULL DEFAULT 0, "
                + COL_PHOTOS_JSON + " TEXT, "
                + COL_IS_SAVED + " INTEGER NOT NULL DEFAULT 0, "
                + COL_IN_HISTORY + " INTEGER NOT NULL DEFAULT 1"
                + ")";
    }

    public void upsertRecord(@NonNull ParkingRecord record, boolean inHistory) {
        ContentValues values = toContentValues(record);
        values.put(COL_IN_HISTORY, inHistory ? 1 : 0);
        getWritableDatabase().insertWithOnConflict(
                TABLE_RECORDS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    public void insertIgnore(@NonNull ParkingRecord record, boolean inHistory) {
        ContentValues values = toContentValues(record);
        values.put(COL_IN_HISTORY, inHistory ? 1 : 0);
        getWritableDatabase().insertWithOnConflict(
                TABLE_RECORDS,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    @NonNull
    public List<ParkingRecord> getSavedRecords() {
        return queryRecords(COL_IS_SAVED + " = 1");
    }

    @NonNull
    public List<ParkingRecord> getHistoryRecords() {
        return queryRecords(COL_IN_HISTORY + " = 1");
    }

    @NonNull
    private List<ParkingRecord> queryRecords(@NonNull String selection) {
        List<ParkingRecord> records = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_RECORDS,
                null,
                selection,
                null,
                null,
                null,
                COL_SAVED_TS + " DESC"
        )) {
            while (cursor.moveToNext()) {
                ParkingRecord record = fromCursor(cursor);
                if (record != null) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    @Nullable
    public ParkingRecord getRecordById(@NonNull String id) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_RECORDS,
                null,
                COL_ID + " = ?",
                new String[]{id},
                null,
                null,
                null,
                "1"
        )) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
        }
        return null;
    }

    public int getSavedCount() {
        return getCountWhere(COL_IS_SAVED + " = 1");
    }

    public int getHistoryCount() {
        return getCountWhere(COL_IN_HISTORY + " = 1");
    }

    private int getCountWhere(@NonNull String whereClause) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_RECORDS + " WHERE " + whereClause,
                null
        )) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }

    public void setSaved(@NonNull String id, boolean isSaved) {
        ContentValues values = new ContentValues();
        values.put(COL_IS_SAVED, isSaved ? 1 : 0);
        if (!isSaved) {
            values.put(COL_IN_HISTORY, 1);
        }
        getWritableDatabase().update(TABLE_RECORDS, values, COL_ID + " = ?", new String[]{id});
    }

    public void updatePlaceLabel(@NonNull String id, @Nullable String placeLabel) {
        ContentValues values = new ContentValues();
        if (placeLabel != null) {
            values.put(COL_PLACE_LABEL, placeLabel);
        } else {
            values.putNull(COL_PLACE_LABEL);
        }
        getWritableDatabase().update(TABLE_RECORDS, values, COL_ID + " = ?", new String[]{id});
    }

    public void updateAddress(@NonNull String id, @Nullable String address) {
        ContentValues values = new ContentValues();
        if (address != null) {
            values.put(COL_ADDRESS, address);
        } else {
            values.putNull(COL_ADDRESS);
        }
        getWritableDatabase().update(TABLE_RECORDS, values, COL_ID + " = ?", new String[]{id});
    }

    public void updateNote(@NonNull String id, @Nullable String note) {
        ContentValues values = new ContentValues();
        if (note != null) {
            values.put(COL_NOTE, note);
        } else {
            values.putNull(COL_NOTE);
        }
        getWritableDatabase().update(TABLE_RECORDS, values, COL_ID + " = ?", new String[]{id});
    }

    public void updatePhotos(@NonNull String id, @NonNull List<ParkingPhotoItem> photos) {
        ContentValues values = new ContentValues();
        String json = ParkingPhotoItem.toJsonString(photos);
        if (json != null) {
            values.put(COL_PHOTOS_JSON, json);
        } else {
            values.putNull(COL_PHOTOS_JSON);
        }

        if (photos.isEmpty()) {
            values.putNull(COL_PHOTO_PATH);
            values.put(COL_PHOTO_CAPTURE_TS, 0L);
            values.putNull(COL_PHOTO_LAT);
            values.putNull(COL_PHOTO_LNG);
            values.put(COL_PHOTO_IMPORT_TS, 0L);
        } else {
            ParkingPhotoItem primary = photos.get(0);
            values.put(COL_PHOTO_PATH, primary.getFilePath());
            values.put(COL_PHOTO_CAPTURE_TS, primary.getOriginalCaptureTimestamp());
            if (primary.hasPhotoLocation()) {
                values.put(COL_PHOTO_LAT, primary.getPhotoLatitude());
                values.put(COL_PHOTO_LNG, primary.getPhotoLongitude());
            } else {
                values.putNull(COL_PHOTO_LAT);
                values.putNull(COL_PHOTO_LNG);
            }
            values.put(COL_PHOTO_IMPORT_TS, primary.getImportTimestamp());
        }
        getWritableDatabase().update(TABLE_RECORDS, values, COL_ID + " = ?", new String[]{id});
    }

    @Nullable
    public ParkingRecord removeFromHistory(@NonNull String id) {
        ParkingRecord existing = getRecordById(id);
        if (existing == null) {
            return null;
        }
        if (existing.isSaved()) {
            ContentValues values = new ContentValues();
            values.put(COL_IN_HISTORY, 0);
            getWritableDatabase().update(TABLE_RECORDS, values, COL_ID + " = ?", new String[]{id});
        } else {
            getWritableDatabase().delete(TABLE_RECORDS, COL_ID + " = ?", new String[]{id});
        }
        return existing;
    }

    @NonNull
    public List<String> clearHistoryPreservingSaved() {
        List<String> deletedPhotos = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query(
                TABLE_RECORDS,
                new String[]{COL_PHOTO_PATH, COL_PHOTOS_JSON},
                COL_IS_SAVED + " = 0",
                null,
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                if (path != null && !path.trim().isEmpty()) {
                    deletedPhotos.add(path);
                }
                List<ParkingPhotoItem> items = ParkingPhotoItem.fromJsonString(cursor.getString(1));
                for (ParkingPhotoItem item : items) {
                    if (!deletedPhotos.contains(item.getFilePath())) {
                        deletedPhotos.add(item.getFilePath());
                    }
                }
            }
        }
        db.delete(TABLE_RECORDS, COL_IS_SAVED + " = 0", null);
        ContentValues values = new ContentValues();
        values.put(COL_IN_HISTORY, 0);
        db.update(TABLE_RECORDS, values, COL_IS_SAVED + " = 1", null);
        return deletedPhotos;
    }

    public void deleteAllRecords() {
        getWritableDatabase().delete(TABLE_RECORDS, null, null);
    }

    public boolean isPhotoReferenced(@NonNull String photoPath) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_RECORDS,
                new String[]{COL_PHOTO_PATH, COL_PHOTOS_JSON},
                COL_PHOTO_PATH + " = ? OR " + COL_PHOTOS_JSON + " IS NOT NULL",
                new String[]{photoPath},
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                if (photoPath.equals(cursor.getString(0))) {
                    return true;
                }
                List<ParkingPhotoItem> items = ParkingPhotoItem.fromJsonString(cursor.getString(1));
                for (ParkingPhotoItem item : items) {
                    if (photoPath.equals(item.getFilePath())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @NonNull
    private static ContentValues toContentValues(@NonNull ParkingRecord record) {
        ContentValues values = new ContentValues();
        values.put(COL_ID, record.getId());
        values.put(COL_LAT, record.getLatitude());
        values.put(COL_LNG, record.getLongitude());
        if (record.getPlaceLabel() != null) {
            values.put(COL_PLACE_LABEL, record.getPlaceLabel());
        } else {
            values.putNull(COL_PLACE_LABEL);
        }
        if (record.getAddress() != null) {
            values.put(COL_ADDRESS, record.getAddress());
        } else {
            values.putNull(COL_ADDRESS);
        }
        values.put(COL_SAVED_TS, record.getSavedTimestamp());
        if (record.getNote() != null) {
            values.put(COL_NOTE, record.getNote());
        } else {
            values.putNull(COL_NOTE);
        }

        List<ParkingPhotoItem> photos = record.getPhotos();
        String json = ParkingPhotoItem.toJsonString(photos);
        if (json != null) {
            values.put(COL_PHOTOS_JSON, json);
        } else {
            values.putNull(COL_PHOTOS_JSON);
        }

        if (record.getPhotoPath() != null) {
            values.put(COL_PHOTO_PATH, record.getPhotoPath());
        } else {
            values.putNull(COL_PHOTO_PATH);
        }
        values.put(COL_PHOTO_CAPTURE_TS, record.getOriginalCaptureTimestamp());
        if (record.hasPhotoLocation()) {
            values.put(COL_PHOTO_LAT, record.getPhotoLatitude());
            values.put(COL_PHOTO_LNG, record.getPhotoLongitude());
        } else {
            values.putNull(COL_PHOTO_LAT);
            values.putNull(COL_PHOTO_LNG);
        }
        values.put(COL_PHOTO_IMPORT_TS, record.getImportTimestamp());
        values.put(COL_IS_SAVED, record.isSaved() ? 1 : 0);
        return values;
    }

    @Nullable
    private static ParkingRecord fromCursor(@NonNull Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID));
        double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LAT));
        double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LNG));
        if (id == null || id.isEmpty() || !DistanceCalculator.isValidCoordinate(lat, lng)) {
            return null;
        }
        int labelIdx = cursor.getColumnIndex(COL_PLACE_LABEL);
        String placeLabel = (labelIdx >= 0 && !cursor.isNull(labelIdx))
                ? cursor.getString(labelIdx)
                : null;

        int addrIdx = cursor.getColumnIndexOrThrow(COL_ADDRESS);
        String address = cursor.isNull(addrIdx) ? null : cursor.getString(addrIdx);

        long savedTs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SAVED_TS));

        int noteIdx = cursor.getColumnIndexOrThrow(COL_NOTE);
        String note = cursor.isNull(noteIdx) ? null : cursor.getString(noteIdx);

        int jsonIdx = cursor.getColumnIndex(COL_PHOTOS_JSON);
        String photosJson = (jsonIdx >= 0 && !cursor.isNull(jsonIdx)) ? cursor.getString(jsonIdx) : null;
        List<ParkingPhotoItem> photos = ParkingPhotoItem.fromJsonString(photosJson);

        if (photos.isEmpty()) {
            int photoIdx = cursor.getColumnIndexOrThrow(COL_PHOTO_PATH);
            String photoPath = cursor.isNull(photoIdx) ? null : cursor.getString(photoIdx);
            if (photoPath != null && !photoPath.trim().isEmpty()) {
                long photoCaptureTs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PHOTO_CAPTURE_TS));
                int photoLatIdx = cursor.getColumnIndexOrThrow(COL_PHOTO_LAT);
                int photoLngIdx = cursor.getColumnIndexOrThrow(COL_PHOTO_LNG);
                double photoLat = Double.NaN;
                double photoLng = Double.NaN;
                if (!cursor.isNull(photoLatIdx) && !cursor.isNull(photoLngIdx)) {
                    double cLat = cursor.getDouble(photoLatIdx);
                    double cLng = cursor.getDouble(photoLngIdx);
                    if (DistanceCalculator.isValidCoordinate(cLat, cLng)) {
                        photoLat = cLat;
                        photoLng = cLng;
                    }
                }
                long photoImportTs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PHOTO_IMPORT_TS));
                photos = Collections.singletonList(
                        new ParkingPhotoItem(photoPath, photoCaptureTs, photoLat, photoLng, photoImportTs)
                );
            }
        }

        int savedIdx = cursor.getColumnIndex(COL_IS_SAVED);
        boolean isSaved = savedIdx >= 0 && cursor.getInt(savedIdx) == 1;

        return new ParkingRecord(
                id,
                lat,
                lng,
                placeLabel,
                address,
                savedTs,
                note,
                photos,
                isSaved
        );
    }
}
