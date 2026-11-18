package com.example.devicewatch.storage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.example.devicewatch.location.DistanceCalculator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages parking photo persistence in app-private storage, EXIF metadata preservation,
 * and safe background bitmap decoding.
 */
public class ParkingPhotoManager {

    public static class PhotoMetadata {
        @NonNull
        public final String internalFilePath;
        public final long capturedAtMillis;
        public final long importedAtMillis;
        public final double latitude;
        public final double longitude;

        public PhotoMetadata(
                @NonNull String internalFilePath,
                long capturedAtMillis,
                long importedAtMillis,
                double latitude,
                double longitude
        ) {
            this.internalFilePath = internalFilePath;
            this.capturedAtMillis = capturedAtMillis;
            this.importedAtMillis = importedAtMillis;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public interface PhotoMetadataCallback {
        void onPhotoReady(@NonNull PhotoMetadata metadata);
        void onPhotoError(@NonNull String message);
    }

    public interface BitmapLoadCallback {
        void onBitmapLoaded(@Nullable Bitmap bitmap);
    }

    private static final String PHOTOS_DIR = "parking_photos";
    private static final int BUFFER_SIZE = 8192;
    private static final AtomicInteger FILE_SEQUENCE = new AtomicInteger(0);

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ParkingPhotoManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    public File createPersistentPhotoFile() throws IOException {
        File dir = new File(appContext.getFilesDir(), PHOTOS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create parking photos directory.");
        }
        return new File(
                dir,
                "spot_" + System.currentTimeMillis() + "_" + FILE_SEQUENCE.incrementAndGet() + ".jpg"
        );
    }

    /**
     * Copies a picked image verbatim into private storage without recompression,
     * preserving and extracting any EXIF capture timestamp and GPS coordinates.
     */
    public void importGalleryPhoto(
            @NonNull Uri sourceUri,
            @Nullable String previousPhotoPath,
            @NonNull PhotoMetadataCallback callback
    ) {
        if (executor.isShutdown()) {
            postError(callback, "Photo manager is unavailable.");
            return;
        }
        executor.execute(() -> {
            File destFile = null;
            try {
                destFile = createPersistentPhotoFile();
                try (InputStream in = appContext.getContentResolver().openInputStream(sourceUri);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    if (in == null) {
                        deleteFileQuietly(destFile);
                        postError(callback, "Unable to open selected image.");
                        return;
                    }
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                if (destFile.length() == 0L) {
                    deleteFileQuietly(destFile);
                    postError(callback, "Selected image file is empty.");
                    return;
                }

                PhotoMetadata metadata = extractMetadataFromFile(destFile.getAbsolutePath());
                deletePhotoFileSync(previousPhotoPath);
                postReady(callback, metadata);
            } catch (IOException | SecurityException e) {
                if (destFile != null) {
                    deleteFileQuietly(destFile);
                }
                String msg = e.getMessage() != null ? e.getMessage() : "Failed to import photo.";
                postError(callback, msg);
            }
        });
    }

    /**
     * Writes capture timestamp and GPS EXIF tags into a newly captured CameraX photo file
     * when supported, without altering or fabricating missing GPS coordinates.
     */
    public void finalizeCapturedPhoto(
            @NonNull File capturedFile,
            long captureTimeMillis,
            @Nullable Location captureLocation,
            @Nullable String previousPhotoPath,
            @NonNull PhotoMetadataCallback callback
    ) {
        if (executor.isShutdown()) {
            postError(callback, "Photo manager is unavailable.");
            return;
        }
        executor.execute(() -> {
            try {
                if (!capturedFile.exists() || capturedFile.length() == 0L) {
                    deleteFileQuietly(capturedFile);
                    postError(callback, "Captured photo file is empty.");
                    return;
                }

                writeExifMetadata(capturedFile, captureTimeMillis, captureLocation);

                double photoLat = Double.NaN;
                double photoLng = Double.NaN;
                if (captureLocation != null
                        && DistanceCalculator.isValidCoordinate(
                                captureLocation.getLatitude(),
                                captureLocation.getLongitude())) {
                    photoLat = captureLocation.getLatitude();
                    photoLng = captureLocation.getLongitude();
                } else {
                    double[] exifLatLng = readExifLatLng(capturedFile.getAbsolutePath());
                    if (exifLatLng != null) {
                        photoLat = exifLatLng[0];
                        photoLng = exifLatLng[1];
                    }
                }

                deletePhotoFileSync(previousPhotoPath);
                postReady(callback, new PhotoMetadata(
                        capturedFile.getAbsolutePath(),
                        captureTimeMillis,
                        0L,
                        photoLat,
                        photoLng
                ));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Failed to save captured photo.";
                postError(callback, msg);
            }
        });
    }

    public void loadSampledBitmap(
            @Nullable String photoPath,
            int reqWidth,
            int reqHeight,
            @NonNull BitmapLoadCallback callback
    ) {
        if (photoPath == null || photoPath.trim().isEmpty() || executor.isShutdown()) {
            mainHandler.post(() -> callback.onBitmapLoaded(null));
            return;
        }
        executor.execute(() -> {
            try {
                File file = new File(photoPath);
                if (!file.exists() || !file.isFile() || !file.canRead()) {
                    mainHandler.post(() -> callback.onBitmapLoaded(null));
                    return;
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(photoPath, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    mainHandler.post(() -> callback.onBitmapLoaded(null));
                    return;
                }

                BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                decodeOpts.inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight);
                Bitmap bitmap = BitmapFactory.decodeFile(photoPath, decodeOpts);
                Bitmap oriented = applyExifOrientation(photoPath, bitmap);
                mainHandler.post(() -> callback.onBitmapLoaded(oriented));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onBitmapLoaded(null));
            }
        });
    }

    public void deletePhotoFile(@Nullable String photoPath) {
        if (photoPath == null || photoPath.trim().isEmpty() || executor.isShutdown()) {
            return;
        }
        executor.execute(() -> deletePhotoFileSync(photoPath));
    }

    public void deleteAllParkingPhotos() {
        if (executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            File photosDir = new File(appContext.getFilesDir(), PHOTOS_DIR);
            File[] files = photosDir.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (file.isFile()) {
                    deleteFileQuietly(file);
                }
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    @NonNull
    private PhotoMetadata extractMetadataFromFile(@NonNull String filePath) {
        long capturedAt = 0L;
        double lat = Double.NaN;
        double lng = Double.NaN;

        try {
            ExifInterface exif = new ExifInterface(filePath);
            long dateTimeOriginal = exif.getDateTimeOriginal();
            long dateTimeDigitized = exif.getDateTimeDigitized();
            long dateTime = exif.getDateTime();

            if (dateTimeOriginal > 0L) {
                capturedAt = dateTimeOriginal;
            } else if (dateTimeDigitized > 0L) {
                capturedAt = dateTimeDigitized;
            } else if (dateTime > 0L) {
                capturedAt = dateTime;
            }

            double[] latLong = exif.getLatLong();
            if (latLong != null && latLong.length == 2
                    && DistanceCalculator.isValidCoordinate(latLong[0], latLong[1])) {
                lat = latLong[0];
                lng = latLong[1];
            }
        } catch (IOException ignored) {
            // File may be a PNG/WebP without EXIF; record import time below without failing.
        }

        long importedAt = capturedAt > 0L ? 0L : System.currentTimeMillis();
        return new PhotoMetadata(filePath, capturedAt, importedAt, lat, lng);
    }

    private static void writeExifMetadata(
            @NonNull File file,
            long captureTimeMillis,
            @Nullable Location captureLocation
    ) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            SimpleDateFormat exifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            exifDateFormat.setTimeZone(TimeZone.getDefault());
            String formattedDate = exifDateFormat.format(new Date(captureTimeMillis));

            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate);
            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate);

            String offset = formatTimezoneOffset(captureTimeMillis);
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset);
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset);

            if (captureLocation != null
                    && DistanceCalculator.isValidCoordinate(
                            captureLocation.getLatitude(),
                            captureLocation.getLongitude())) {
                exif.setGpsInfo(captureLocation);
            }
            exif.saveAttributes();
        } catch (IOException | IllegalArgumentException ignored) {
            // Keep photo saved even if container format does not support EXIF attribute editing.
        }
    }

    @Nullable
    private static double[] readExifLatLng(@NonNull String filePath) {
        try {
            ExifInterface exif = new ExifInterface(filePath);
            double[] latLong = exif.getLatLong();
            if (latLong != null && latLong.length == 2
                    && DistanceCalculator.isValidCoordinate(latLong[0], latLong[1])) {
                return latLong;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    @NonNull
    private static String formatTimezoneOffset(long timeMillis) {
        int offsetMillis = TimeZone.getDefault().getOffset(timeMillis);
        int totalMinutes = offsetMillis / 60000;
        char sign = totalMinutes >= 0 ? '+' : '-';
        int absMinutes = Math.abs(totalMinutes);
        return String.format(Locale.US, "%c%02d:%02d", sign, absMinutes / 60, absMinutes % 60);
    }

    private void deletePhotoFileSync(@Nullable String photoPath) {
        if (photoPath == null || photoPath.trim().isEmpty()) {
            return;
        }
        try {
            File photosDir = new File(appContext.getFilesDir(), PHOTOS_DIR).getCanonicalFile();
            File target = new File(photoPath).getCanonicalFile();
            // Only delete files inside this app's private parking_photos directory.
            if (target.exists() && target.getPath().startsWith(photosDir.getPath() + File.separator)) {
                deleteFileQuietly(target);
            }
        } catch (IOException ignored) {
        }
    }

    private static void deleteFileQuietly(@NonNull File file) {
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Nullable
    private static Bitmap applyExifOrientation(@NonNull String path, @Nullable Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
            int degrees = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            }
            if (degrees == 0) {
                return bitmap;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
            );
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (IOException | OutOfMemoryError e) {
            return bitmap;
        }
    }

    private static int calculateInSampleSize(
            @NonNull BitmapFactory.Options options,
            int reqWidth,
            int reqHeight
    ) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private void postReady(@NonNull PhotoMetadataCallback callback, @NonNull PhotoMetadata metadata) {
        mainHandler.post(() -> callback.onPhotoReady(metadata));
    }

    private void postError(@NonNull PhotoMetadataCallback callback, @NonNull String message) {
        mainHandler.post(() -> callback.onPhotoError(message));
    }
}
