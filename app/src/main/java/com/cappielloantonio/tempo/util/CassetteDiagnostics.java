package com.cappielloantonio.tempo.util;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.cappielloantonio.tempo.BuildConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Native crash + diagnostics client for the Cassette diagnostics spine (the
 * Android twin of the iOS reporter). Modeled on {@link CassetteSyncApi}: OkHttp
 * plus hand-assembled {@link JsonObject} envelopes, no Gson serializer.
 *
 * <p>Design:
 * <ul>
 *   <li>{@link #install(Context)} arms a default uncaught-exception handler that
 *       persists each crash to <code>filesDir/diagnostics/pending/</code> with a
 *       freshly-minted idempotency_key, chains to the previous handler, and lets
 *       the process die. Nothing is uploaded from the dying process.</li>
 *   <li>On the next launch {@link #flushPending(Context)} POSTs each pending
 *       report to the spine. It deletes a file on success <em>and</em> on
 *       permanent rejection (HTTP 400/413 and other 4xx) so a bad body can never
 *       become a poison pill; it retries only on network error, 5xx, or 429.</li>
 *   <li>Each crash mints a distinct key → a distinct file. The pending dir is
 *       capped at {@link #MAX_PENDING_FILES}: an early-boot crash-loop drops its
 *       oldest reports instead of filling the disk.</li>
 *   <li>Consent is OPT-OUT for crashes: reports carry
 *       <code>consent.user_consented=false</code>. There is no toggle in v1.</li>
 * </ul>
 *
 * <p>Player errors are NOT auto-reported — MediaService feeds them into
 * {@link #breadcrumb(String)}, a small ring buffer that enriches the next crash.
 *
 * <p>Residual gap: a JVM handler cannot catch native SIGSEGV (e.g. in ffmpeg) or
 * ANRs. {@link #harvestExitReasons(Context)} closes part of that on API 30+ by
 * reading {@link ApplicationExitInfo} for native crashes and ANRs on next launch.
 */
public final class CassetteDiagnostics {
    private static final String TAG = "CassetteDiagnostics";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int SCHEMA_VERSION = 1;
    /** Keep at most this many pending reports; oldest are dropped (crash-loop cap). */
    private static final int MAX_PENDING_FILES = 50;
    /** Latest breadcrumbs carried inline in context (spine budget is ~30). */
    private static final int MAX_BREADCRUMBS = 30;
    /** Top frames kept in the crash_summary head. */
    private static final int MAX_FRAMES = 20;

    private static final ArrayDeque<String> BREADCRUMBS = new ArrayDeque<>();

    private static volatile Context appContext;
    @Nullable private static volatile Thread.UncaughtExceptionHandler previousHandler;

    private CassetteDiagnostics() {
    }

    // ------------------------------------------------------------------
    // Lifecycle

    /**
     * Install the crash handler. Idempotent: chains to whatever handler is
     * already the default (unless that is already this one).
     */
    public static void install(Context context) {
        appContext = context.getApplicationContext();

        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CassetteUncaughtHandler) return;

        previousHandler = current;
        Thread.setDefaultUncaughtExceptionHandler(new CassetteUncaughtHandler());
    }

    /** Flush pending reports on a low-priority background thread (call at startup). */
    public static void flushPendingAsync(Context context) {
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                flushPending(app);
            } catch (Throwable t) {
                // Diagnostics must never crash the app it reports on.
                Log.w(TAG, "flushPending failed", t);
            }
        }, "cassette-diagnostics-flush");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // ------------------------------------------------------------------
    // Breadcrumbs (enrich the next crash; never uploaded on their own)

    public static void breadcrumb(String message) {
        if (message == null) return;
        String line = iso(System.currentTimeMillis()) + " " + message;
        synchronized (BREADCRUMBS) {
            BREADCRUMBS.addLast(line);
            while (BREADCRUMBS.size() > MAX_BREADCRUMBS) {
                BREADCRUMBS.removeFirst();
            }
        }
    }

    // ------------------------------------------------------------------
    // Crash persistence (runs inside the dying process — allocation-light)

    private static final class CassetteUncaughtHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            try {
                persistCrash(thread, throwable);
            } catch (Throwable ignored) {
                // Never mask the original crash with a reporting failure.
            }

            Thread.UncaughtExceptionHandler previous = previousHandler;
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                // No prior handler: end the process ourselves.
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
    }

    private static void persistCrash(Thread thread, Throwable throwable) {
        Context ctx = appContext;
        if (ctx == null) return;

        File dir = pendingDir(ctx);
        if (dir == null) return;

        long occurredAt = System.currentTimeMillis();
        String idempotencyKey = UUID.randomUUID().toString();
        JsonObject envelope = buildCrashEnvelope(thread, throwable, idempotencyKey, occurredAt);

        // Filename is time-sortable so the cap prunes the oldest reports.
        File file = new File(dir, occurredAt + "-" + idempotencyKey + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "persistCrash: write failed: " + e.getMessage());
            return;
        }

        prunePending(dir);
    }

    private static JsonObject buildCrashEnvelope(Thread thread, Throwable throwable, String idempotencyKey, long occurredAtMillis) {
        JsonObject body = baseEnvelope("crash", idempotencyKey, occurredAtMillis);
        body.addProperty("severity", "fatal");
        body.addProperty("title", title(throwable));
        body.addProperty("fingerprint", fingerprint(throwable));
        body.add("crash_summary", crashSummary(throwable));
        body.add("context", crashContext(thread));
        return body;
    }

    /** Fields shared by every report kind. */
    private static JsonObject baseEnvelope(String reportType, String idempotencyKey, long occurredAtMillis) {
        JsonObject body = new JsonObject();
        body.addProperty("schema_version", SCHEMA_VERSION);
        body.addProperty("idempotency_key", idempotencyKey);
        body.addProperty("report_type", reportType);
        body.addProperty("platform", "android");
        body.addProperty("app_version", BuildConfig.VERSION_NAME);
        body.addProperty("app_build", String.valueOf(BuildConfig.VERSION_CODE));
        body.addProperty("os_name", "Android");
        body.addProperty("os_version", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
        body.addProperty("device_model", Build.MODEL != null ? Build.MODEL : "Android");
        // device_id is the account registry identity (optional, correlation only);
        // install_id is the required anonymous per-install correlation key.
        body.addProperty("device_id", Preferences.getCassetteDeviceId());
        body.addProperty("install_id", Preferences.getCassetteInstallId());
        body.addProperty("occurred_at", iso(occurredAtMillis));
        // OPT-OUT: crashes/hangs auto-upload with user_consented=false. A full
        // diagnostic export (an explicit user share) would set this true.
        body.add("consent", consent(false));
        body.add("client_meta", clientMeta());
        return body;
    }

    private static JsonObject crashSummary(Throwable throwable) {
        JsonObject summary = new JsonObject();
        summary.addProperty("name", throwable.getClass().getName());

        String message = throwable.getMessage();
        if (message != null) summary.addProperty("message", truncate(message, 500));

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            summary.addProperty("cause", cause.getClass().getName());
        }

        JsonArray frames = new JsonArray();
        StackTraceElement[] stack = throwable.getStackTrace();
        int count = Math.min(stack.length, MAX_FRAMES);
        for (int i = 0; i < count; i++) {
            frames.add(stack[i].toString());
        }
        summary.add("frames", frames);
        return summary;
    }

    private static JsonObject crashContext(Thread thread) {
        JsonObject context = new JsonObject();
        if (thread != null) context.addProperty("thread", thread.getName());

        JsonArray crumbs = new JsonArray();
        synchronized (BREADCRUMBS) {
            for (String crumb : BREADCRUMBS) crumbs.add(crumb);
        }
        context.add("breadcrumbs", crumbs);
        return context;
    }

    private static JsonObject consent(boolean userConsented) {
        JsonObject consent = new JsonObject();
        consent.addProperty("user_consented", userConsented);
        consent.addProperty("upload_enabled", true);
        consent.addProperty("contains_pii", false);
        consent.addProperty("redacted", false);
        return consent;
    }

    private static JsonObject clientMeta() {
        JsonObject meta = new JsonObject();
        meta.addProperty("locale", Locale.getDefault().toString());
        meta.addProperty("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
        meta.addProperty("android_sdk", Build.VERSION.SDK_INT);
        return meta;
    }

    private static String title(Throwable throwable) {
        String message = throwable.getMessage();
        String simpleName = throwable.getClass().getSimpleName();
        return message != null ? truncate(simpleName + ": " + message, 200) : simpleName;
    }

    private static String fingerprint(Throwable throwable) {
        StackTraceElement[] stack = throwable.getStackTrace();
        String top = stack.length > 0
                ? stack[0].getClassName() + "." + stack[0].getMethodName()
                : "unknown";
        return throwable.getClass().getName() + "@" + top;
    }

    // ------------------------------------------------------------------
    // Flush (next launch)

    private enum Outcome {
        /** 2xx — the spine accepted (or deduped) the report. */
        SUCCESS,
        /** 4xx (incl. 400/413) — the exact body will be rejected forever; drop it. */
        PERMANENT_REJECT,
        /** Network error, 5xx, or 429 — leave the file for the next launch. */
        RETRY
    }

    /**
     * POST every pending report. First harvests any OS-recorded native crashes /
     * ANRs (API 30+) into new pending files, so they ride out in the same pass.
     */
    public static void flushPending(Context context) {
        Context ctx = context.getApplicationContext();
        harvestExitReasons(ctx);

        File dir = pendingDir(ctx);
        if (dir == null) return;

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        // Oldest first: send in the order faults happened.
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        String bearer = Preferences.getCassetteBearerToken(); // may be null (anonymous is allowed)
        OkHttpClient client = client();

        for (File file : files) {
            if (!file.isFile()) continue;
            Outcome outcome = postReport(client, file, bearer);
            if (outcome == Outcome.SUCCESS || outcome == Outcome.PERMANENT_REJECT) {
                // Delete on success AND on permanent rejection — the poison-pill guard.
                if (!file.delete()) {
                    Log.w(TAG, "flush: could not delete " + file.getName());
                }
            }
            // Outcome.RETRY: leave the file in place for the next launch.
        }
    }

    private static Outcome postReport(OkHttpClient client, File file, @Nullable String bearer) {
        byte[] payload;
        try {
            payload = readAll(file);
        } catch (IOException e) {
            // Unreadable file would block the queue forever — quarantine it.
            Log.w(TAG, "flush: unreadable report " + file.getName() + ": " + e.getMessage());
            return Outcome.PERMANENT_REJECT;
        }

        Request.Builder builder = new Request.Builder()
                .url(Cassette.BASE_URL + "/api/diagnostics/reports")
                .post(RequestBody.create(payload, JSON));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);

        try (Response response = client.newCall(builder.build()).execute()) {
            int code = response.code();
            if (response.isSuccessful()) {
                return Outcome.SUCCESS;
            }
            if (code == 429 || code >= 500) {
                Log.w(TAG, "flush: transient HTTP " + code + ", will retry " + file.getName());
                return Outcome.RETRY;
            }
            // 400 invalid_report, 413 payload_too_large, or any other 4xx: retrying
            // the identical body would loop forever. Drop it.
            Log.w(TAG, "flush: permanent HTTP " + code + ", dropping " + file.getName());
            return Outcome.PERMANENT_REJECT;
        } catch (IOException e) {
            // Network error — keep the file and try again next launch.
            return Outcome.RETRY;
        }
    }

    // ------------------------------------------------------------------
    // ApplicationExitInfo (API 30+): native crashes + ANRs the JVM can't see

    private static void harvestExitReasons(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;

        File dir = pendingDir(ctx);
        if (dir == null) return;

        long highwater = Preferences.getCassetteDiagnosticsExitHighwater();
        long newHighwater = highwater;

        try {
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 0);
            for (ApplicationExitInfo exit : exits) {
                long timestamp = exit.getTimestamp();
                if (timestamp <= highwater) continue;
                if (timestamp > newHighwater) newHighwater = timestamp;

                int reason = exit.getReason();
                // REASON_CRASH (JVM) is already captured by our uncaught handler —
                // only harvest what the handler structurally cannot see.
                String reportType;
                if (reason == ApplicationExitInfo.REASON_ANR) {
                    reportType = "hang";
                } else if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    reportType = "crash";
                } else {
                    continue;
                }

                writeExitReport(dir, exit, reportType, timestamp);
            }
        } catch (Throwable t) {
            Log.w(TAG, "harvestExitReasons failed", t);
        } finally {
            if (newHighwater > highwater) {
                Preferences.setCassetteDiagnosticsExitHighwater(newHighwater);
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void writeExitReport(File dir, ApplicationExitInfo exit, String reportType, long occurredAt) {
        String idempotencyKey = UUID.randomUUID().toString();
        JsonObject body = baseEnvelope(reportType, idempotencyKey, occurredAt);
        body.addProperty("severity", "hang".equals(reportType) ? "error" : "fatal");

        boolean isNative = exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE;
        if (isNative) body.addProperty("component", "native");

        String reasonName = exitReasonName(exit.getReason());
        String description = exit.getDescription();
        body.addProperty("title", description != null ? truncate(reasonName + ": " + description, 200) : reasonName);
        body.addProperty("fingerprint", "exit:" + reasonName);

        JsonObject summary = new JsonObject();
        summary.addProperty("name", reasonName);
        if (description != null) summary.addProperty("description", truncate(description, 500));
        summary.addProperty("importance", exit.getImportance());
        summary.addProperty("status", exit.getStatus());
        // The ANR / tombstone trace is large — it belongs in a rolling_trace
        // attachment, not inline. Kept out here to stay under the context budget.
        body.add("crash_summary", summary);

        File file = new File(dir, occurredAt + "-" + idempotencyKey + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "writeExitReport: " + e.getMessage());
            return;
        }
        prunePending(dir);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "native_crash";
            default:
                return "exit_" + reason;
        }
    }

    // ------------------------------------------------------------------
    // Storage + IO helpers

    @Nullable
    private static File pendingDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "diagnostics/pending");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            Log.w(TAG, "pendingDir: could not create " + dir.getAbsolutePath());
            return null;
        }
        return dir;
    }

    private static void prunePending(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_PENDING_FILES) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = files.length - MAX_PENDING_FILES;
        for (int i = 0; i < toDelete; i++) {
            if (!files[i].delete()) {
                Log.w(TAG, "prunePending: could not delete " + files[i].getName());
            }
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static OkHttpClient client() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** UTC ISO-8601 with millis, matching CassettePlaySync's play-spine format. */
    private static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
