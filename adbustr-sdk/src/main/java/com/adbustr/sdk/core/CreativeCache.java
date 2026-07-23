package com.adbustr.sdk.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.Locale;

/**
 * Downloads creatives ahead of display.
 *
 * <p>Images are decoded into memory (downsampled to the screen so a 4K creative
 * can't OOM a low-end phone). Videos are streamed to a file in the app cache so
 * playback starts without a network stall — MAX gives us a few seconds total,
 * and a mid-roll buffer would read as a broken ad.
 */
public final class CreativeCache {

    private static final String CACHE_DIR = "adbustr";
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 10;
    /** Files older than this are pruned on the next video download. */
    private static final long MAX_FILE_AGE_MILLIS = 24 * 60 * 60 * 1000L;

    public interface BitmapCallback {
        /** Null on failure. */
        void onResult(Bitmap bitmap);
    }

    public interface FileCallback {
        /** Null on failure. */
        void onResult(File file);
    }

    private CreativeCache() {
    }

    /** Downloads and decodes an image. Callback fires on the IO thread. */
    public static void loadBitmap(final String url, final int maxWidth, final int maxHeight,
                                  final BitmapCallback callback) {
        Threads.io(new Runnable() {
            @Override
            public void run() {
                callback.onResult(fetchBitmap(url, maxWidth, maxHeight));
            }
        });
    }

    /** Downloads a video to the cache dir. Callback fires on the IO thread. */
    public static void loadVideoFile(final Context context, final String url,
                                     final FileCallback callback) {
        Threads.io(new Runnable() {
            @Override
            public void run() {
                callback.onResult(fetchFile(context, url));
            }
        });
    }

    private static Bitmap fetchBitmap(String url, int maxWidth, int maxHeight) {
        Http.Result result = Http.get(url, DOWNLOAD_TIMEOUT_SECONDS);
        if (!result.isSuccess() || result.body == null || result.body.length == 0) {
            SdkLog.d("image download failed: " + url);
            return null;
        }

        byte[] bytes = result.body;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight);
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) {
                SdkLog.d("image decode failed: " + url);
            }
            return bitmap;
        } catch (OutOfMemoryError e) {
            SdkLog.w("out of memory decoding creative: " + url);
            return null;
        }
    }

    private static File fetchFile(Context context, String url) {
        File dir = cacheDir(context);
        if (dir == null) {
            return null;
        }

        prune(dir);

        File destination = new File(dir, fileNameFor(url));
        if (destination.exists() && destination.length() > 0) {
            SdkLog.d("creative cache hit: " + url);
            return destination;
        }

        // Download to a temp name and rename on success, so an interrupted
        // download can never be mistaken for a cache hit next time.
        File temp = new File(dir, destination.getName() + ".part");
        if (!Http.download(url, temp, DOWNLOAD_TIMEOUT_SECONDS)) {
            deleteQuietly(temp);
            return null;
        }
        if (!temp.renameTo(destination)) {
            deleteQuietly(destination);
            if (!temp.renameTo(destination)) {
                deleteQuietly(temp);
                return null;
            }
        }
        return destination;
    }

    private static File cacheDir(Context context) {
        File dir = new File(context.getApplicationContext().getCacheDir(), CACHE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            SdkLog.w("cannot create creative cache dir " + dir);
            return null;
        }
        return dir;
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MAX_FILE_AGE_MILLIS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                deleteQuietly(file);
            }
        }
    }

    private static String fileNameFor(String url) {
        // Hash rather than sanitize: creative URLs carry query strings and can
        // exceed the filesystem's name length limit.
        return String.format(Locale.US, "cr_%08x_%d", url.hashCode(), url.length());
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            SdkLog.d("could not delete " + file);
        }
    }

    private static int sampleSize(int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            return 1;
        }
        int sample = 1;
        while (width / (sample * 2) >= maxWidth && height / (sample * 2) >= maxHeight) {
            sample *= 2;
        }
        return sample;
    }
}
