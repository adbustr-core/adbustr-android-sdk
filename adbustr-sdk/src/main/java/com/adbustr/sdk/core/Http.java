package com.adbustr.sdk.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Minimal HTTP layer over HttpURLConnection. Kept dependency-free on purpose —
 * see the module's build.gradle for why.
 *
 * <p>Every method here blocks and must be called from {@link Threads#io}.
 */
public final class Http {

    /** Bytes we are willing to buffer for a single creative. */
    private static final int MAX_BODY_BYTES = 12 * 1024 * 1024;

    private static final int BUFFER_SIZE = 16 * 1024;

    private Http() {
    }

    /** Outcome of a request: either a body, or a classified failure. */
    public static final class Result {

        public final int statusCode;
        public final byte[] body;
        /** Null on success. */
        public final Failure failure;

        private Result(int statusCode, byte[] body, Failure failure) {
            this.statusCode = statusCode;
            this.body = body;
            this.failure = failure;
        }

        public boolean isSuccess() {
            return failure == null;
        }

        public String bodyAsString() {
            if (body == null) {
                return null;
            }
            try {
                return new String(body, "UTF-8");
            } catch (IOException e) {
                return null;
            }
        }
    }

    public enum Failure {
        TIMEOUT,
        NETWORK,
        /** 4xx — retrying will not help. */
        CLIENT_ERROR,
        /** 5xx — worth one retry. */
        SERVER_ERROR
    }

    public static Result postJson(String url, String json, String apiKey, int timeoutSeconds) {
        HttpURLConnection connection = null;
        try {
            connection = open(new URL(url), timeoutSeconds);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("X-Adbustr-Key", apiKey == null ? "" : apiKey);

            byte[] payload = json.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);

            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                closeQuietly(out);
            }

            return readResponse(connection);
        } catch (SocketTimeoutException e) {
            return new Result(0, null, Failure.TIMEOUT);
        } catch (Exception e) {
            SdkLog.d("POST failed: " + e);
            return new Result(0, null, Failure.NETWORK);
        } finally {
            disconnect(connection);
        }
    }

    /** GET returning the raw body. Used for creatives. */
    public static Result get(String url, int timeoutSeconds) {
        HttpURLConnection connection = null;
        try {
            connection = open(new URL(url), timeoutSeconds);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            return readResponse(connection);
        } catch (SocketTimeoutException e) {
            return new Result(0, null, Failure.TIMEOUT);
        } catch (Exception e) {
            SdkLog.d("GET failed: " + e);
            return new Result(0, null, Failure.NETWORK);
        } finally {
            disconnect(connection);
        }
    }

    /**
     * Fire-and-forget GET for tracking pixels: the status code is all we need,
     * so the body is drained and dropped without buffering.
     */
    public static boolean ping(String url, int timeoutSeconds) {
        HttpURLConnection connection = null;
        try {
            connection = open(new URL(url), timeoutSeconds);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            drain(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            disconnect(connection);
        }
    }

    /**
     * Streams a URL straight to disk. Returns false and leaves no partial file
     * behind on any failure — a half-written mp4 would play as a broken ad.
     */
    public static boolean download(String url, java.io.File destination, int timeoutSeconds) {
        HttpURLConnection connection = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            connection = open(new URL(url), timeoutSeconds);
            connection.setRequestMethod("GET");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return false;
            }

            in = connection.getInputStream();
            out = new java.io.FileOutputStream(destination);

            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    SdkLog.w("creative exceeds " + MAX_BODY_BYTES + " bytes, aborting: " + url);
                    return false;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            SdkLog.d("download failed: " + e);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            disconnect(connection);
        }
    }

    private static HttpURLConnection open(URL url, int timeoutSeconds) throws IOException {
        int timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static Result readResponse(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();

        if (code >= 400) {
            drain(connection.getErrorStream());
            return new Result(code, null, code < 500 ? Failure.CLIENT_ERROR : Failure.SERVER_ERROR);
        }

        InputStream stream = connection.getInputStream();
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            stream = new GZIPInputStream(stream);
        }

        try {
            return new Result(code, readAll(stream), null);
        } finally {
            closeQuietly(stream);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(BUFFER_SIZE);
        byte[] chunk = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_BODY_BYTES) {
                throw new IOException("response exceeds " + MAX_BODY_BYTES + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void drain(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            byte[] chunk = new byte[BUFFER_SIZE];
            while (in.read(chunk) != -1) {
                // Draining lets the connection return to the keep-alive pool.
            }
        } catch (IOException ignored) {
            // Best effort.
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            connection.disconnect();
        }
    }
}
