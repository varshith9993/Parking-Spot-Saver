package com.example.devicewatch.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Local SQLite database storing saved parking records and archived parking history separately
 * with full photo metadata and zero external dependencies.
 */
public class ParkingDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "parking_spot_saver.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE_SAVED = "saved_records";
    public static final String TABLE_HISTORY = "history_records";

    private static final String COL_ID = "id";
    private static final String COL_LAT = "latitude";
    private static final String COL_LNG = "longitude";
    private static final String COL_ADDRESS = "address";
    private static final String COL_SAVED_TS = "saved_timestamp";
    private static final String COL_NOTE = "note";
    private static final String COL_PHOTO_PATH = "photo_path";
    private static final String COL_PHOTO_CAPTURE_TS = "photo_capture_timestamp";
    private static final String COL_PHOTO_LAT = "photo_latitude";
    private static final String COL_PHOTO_LNG = "photo_longitude";
    private static final String COL_PHOTO_IMPORT_TS = "photo_import_timestamp";

    public ParkingDatabaseHelper(@NonNull Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(buildCreateTableSql(TABLE_SAVED));
        db.execSQL(buildCreateTableSql(TABLE_HISTORY));
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future schema migrations go here without dropping user data.
    }

    private static String buildCreateTableSql(@NonNull String tableName) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + COL_ID + " TEXT PRIMARY KEY, "
                + COL_LAT + " REAL NOT NULL, "
                + COL_LNG + " REAL NOT NULL, "
                + COL_ADDRESS + " TEXT, "
                + COL_SAVED_TS + " INTEGER NOT NULL, "
                + COL_NOTE + " TEXT, "
                + COL_PHOTO_PATH + " TEXT, "
                + COL_PHOTO_CAPTURE_TS + " INTEGER NOT NULL DEFAULT 0, "
                + COL_PHOTO_LAT + " REAL, "
                + COL_PHOTO_LNG + " REAL, "
                + COL_PHOTO_IMPORT_TS + " INTEGER NOT NULL DEFAULT 0"
                + ")";
    }

    public void upsertRecord(@NonNull String table, @NonNull ParkingRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict(table, null, toContentValues(record), SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void insertIgnore(@NonNull String table, @NonNull ParkingRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict(table, null, toContentValues(record), SQLiteDatabase.CONFLICT_IGNORE);
    }

    @NonNull
    public List<ParkingRecord> getAllRecords(@NonNull String table) {
        List<ParkingRecord> records = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(
                table,
                null,
                null,
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
    public ParkingRecord getRecordById(@NonNull String table, @NonNull String id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(
                table,
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

    public int getCount(@NonNull String table) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }

    public void updateAddress(@NonNull String table, @NonNull String id, @Nullable String address) {
        ContentValues values = new ContentValues();
        if (address != null) {
            values.put(COL_ADDRESS, address);
        } else {
            values.putNull(COL_ADDRESS);
        }
        getWritableDatabase().update(table, values, COL_ID + " = ?", new String[]{id});
    }

    public void updateNote(@NonNull String table, @NonNull String id, @Nullable String note) {
        ContentValues values = new ContentValues();
        if (note != null) {
            values.put(COL_NOTE, note);
        } else {
            values.putNull(COL_NOTE);
        }
        getWritableDatabase().update(table, values, COL_ID + " = ?", new String[]{id});
    }

    public void updatePhotoMetadata(
            @NonNull String table,
            @NonNull String id,
            @Nullable String photoPath,
            long captureTimestamp,
            double photoLat,
            double photoLng,
            long importTimestamp
    ) {
        ContentValues values = new ContentValues();
        if (photoPath != null) {
            values.put(COL_PHOTO_PATH, photoPath);
        } else {
            values.putNull(COL_PHOTO_PATH);
        }
        values.put(COL_PHOTO_CAPTURE_TS, captureTimestamp);
        if (DistanceCalculator.isValidCoordinate(photoLat, photoLng)) {
            values.put(COL_PHOTO_LAT, photoLat);
            values.put(COL_PHOTO_LNG, photoLng);
        } else {
            values.putNull(COL_PHOTO_LAT);
            values.putNull(COL_PHOTO_LNG);
        }
        values.put(COL_PHOTO_IMPORT_TS, importTimestamp);
        getWritableDatabase().update(table, values, COL_ID + " = ?", new String[]{id});
    }

    @Nullable
    public ParkingRecord deleteById(@NonNull String table, @NonNull String id) {
        ParkingRecord existing = getRecordById(table, id);
        if (existing != null) {
            getWritableDatabase().delete(table, COL_ID + " = ?", new String[]{id});
        }
        return existing;
    }

    @NonNull
    public List<String> clearTableAndCollectPhotos(@NonNull String table) {
        List<String> photos = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query(
                table,
                new String[]{COL_PHOTO_PATH},
                COL_PHOTO_PATH + " IS NOT NULL",
                null,
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                if (path != null && !path.trim().isEmpty()) {
                    photos.add(path);
                }
            }
        }
        db.delete(table, null, null);
        return photos;
    }

    public boolean isPhotoReferencedInTable(@NonNull String table, @NonNull String photoPath) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(
                table,
                new String[]{COL_ID},
                COL_PHOTO_PATH + " = ?",
                new String[]{photoPath},
                null,
                null,
                null,
                "1"
        )) {
            return cursor.moveToFirst();
        }
    }

    @NonNull
    private static ContentValues toContentValues(@NonNull ParkingRecord record) {
        ContentValues values = new ContentValues();
        values.put(COL_ID, record.getId());
        values.put(COL_LAT, record.getLatitude());
        values.put(COL_LNG, record.getLongitude());
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
        int addrIdx = cursor.getColumnIndexOrThrow(COL_ADDRESS);
        String address = cursor.isNull(addrIdx) ? null : cursor.getString(addrIdx);

        long savedTs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SAVED_TS));

        int noteIdx = cursor.getColumnIndexOrThrow(COL_NOTE);
        String note = cursor.isNull(noteIdx) ? null : cursor.getString(noteIdx);

        int photoIdx = cursor.getColumnIndexOrThrow(COL_PHOTO_PATH);
        String photoPath = cursor.isNull(photoIdx) ? null : cursor.getString(photoIdx);

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

        return new ParkingRecord(
                id,
                lat,
                lng,
                address,
                savedTs,
                note,
                photoPath,
                photoCaptureTs,
                photoLat,
                photoLng,
                photoImportTs
        );
    }
}
