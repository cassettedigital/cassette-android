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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    /** Suffix for the trace sidecar written next to an ANR / native exit report. */
    private static final String TRACE_SIDECAR_SUFFIX = ".rolling_trace";
    /** Wire content type for the ANR / tombstone trace attachment. */
    private static final String TRACE_CONTENT_TYPE = "text/plain";
    /** Screenshot attachment content type (canonical per the diagnostics contract). */
    private static final String SCREENSHOT_CONTENT_TYPE = "image/png";
    /** Cap the harvested ANR / tombstone trace so a pathological dump can't fill disk. */
    private static final int MAX_TRACE_BYTES = 512 * 1024;
    /** Cap free-text feedback so the whole envelope stays well under the 512KB body cap. */
    private static final int MAX_FEEDBACK_CHARS = 4000;
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

    /** Fields shared by every report kind. AUTO reports (crash/hang/native)
     *  carry user_consented=false and reflect the opt-out gate in upload_enabled;
     *  a user-initiated report (feedback) overrides via {@link #manualEnvelope}. */
    private static JsonObject baseEnvelope(String reportType, String idempotencyKey, long occurredAtMillis) {
        return identityEnvelope(reportType, idempotencyKey, occurredAtMillis,
                /* userConsented */ false, /* uploadEnabled */ isAutoUploadEnabled());
    }

    /** Envelope for a user-initiated report (feedback / export): consent is
     *  explicit and upload is always enabled regardless of the opt-out flag. */
    private static JsonObject manualEnvelope(String reportType, String idempotencyKey, long occurredAtMillis) {
        return identityEnvelope(reportType, idempotencyKey, occurredAtMillis,
                /* userConsented */ true, /* uploadEnabled */ true);
    }

    private static JsonObject identityEnvelope(String reportType, String idempotencyKey,
                                               long occurredAtMillis, boolean userConsented,
                                               boolean uploadEnabled) {
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
        body.add("consent", consent(userConsented, uploadEnabled));
        body.add("client_meta", clientMeta());
        return body;
    }

    /**
     * Whether AUTO reports (crash / hang / native) may upload. Anonymous installs
     * are always-on; a signed-in account can opt out via the web dashboard, which
     * lands here through {@link CassetteSyncApi.Account#diagnosticsConsent}. The
     * server records consent verbatim and never blocks — the real stop is here,
     * client-side, by not POSTing when this is false.
     */
    private static boolean isAutoUploadEnabled() {
        // No bearer ⇒ anonymous ⇒ always-on (the opt-out only governs the account).
        if (Preferences.getCassetteBearerToken() == null) return true;
        return Preferences.getCassetteDiagnosticsConsent();
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

    private static JsonObject consent(boolean userConsented, boolean uploadEnabled) {
        JsonObject consent = new JsonObject();
        consent.addProperty("user_consented", userConsented);
        // No longer hardcoded: reflects the account opt-out for auto reports so the
        // server's consent record matches what the client actually did.
        consent.addProperty("upload_enabled", uploadEnabled);
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
     *
     * <p>Every pending file is an AUTO report (crash / hang / native). When the
     * signed-in account has opted out ({@link #isAutoUploadEnabled} false) they
     * are dropped without uploading — the opt-out is enforced HERE, client-side.
     * Manual feedback never lands in the pending queue, so it is unaffected.
     *
     * <p>An ANR / native exit may have a companion <code>*.rolling_trace</code>
     * sidecar (the tombstone / ANR trace, too large to inline). After the report
     * POSTs, that sidecar is uploaded to R2 via the presigned URL the ingest route
     * returns, then the attachment two-step is finalized.
     */
    public static void flushPending(Context context) {
        Context ctx = context.getApplicationContext();
        harvestExitReasons(ctx);

        File dir = pendingDir(ctx);
        if (dir == null) return;

        File[] all = dir.listFiles();
        if (all == null || all.length == 0) return;

        // Only *.json are report envelopes; *.rolling_trace are attachment sidecars.
        List<File> reports = new ArrayList<>();
        for (File f : all) {
            if (f.isFile() && f.getName().endsWith(".json")) reports.add(f);
        }
        if (reports.isEmpty()) return;

        // Opt-out: signed-in accounts that turned diagnostics off never upload the
        // auto queue. Drop the reports (and any sidecars) so they don't accumulate.
        if (!isAutoUploadEnabled()) {
            Log.d(TAG, "flush: diagnostics opted out, dropping " + reports.size() + " pending report(s)");
            for (File report : reports) {
                deleteReportAndSidecar(report);
            }
            return;
        }

        // Oldest first: send in the order faults happened.
        reports.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        String bearer = Preferences.getCassetteBearerToken(); // may be null (anonymous is allowed)
        OkHttpClient client = client();

        for (File file : reports) {
            PostResult result = postReport(client, file, bearer);
            if (result.outcome == Outcome.SUCCESS || result.outcome == Outcome.PERMANENT_REJECT) {
                if (result.outcome == Outcome.SUCCESS && result.reportId != null) {
                    // Ship the ANR / tombstone trace as a rolling_trace attachment.
                    uploadSidecarTrace(client, file, result);
                }
                // Delete on success AND on permanent rejection — the poison-pill guard.
                deleteReportAndSidecar(file);
            }
            // Outcome.RETRY: leave the file (and its sidecar) in place for next launch.
        }
    }

    /** Result of a report POST: the outcome plus (on success) the server id and
     *  the presigned attachment upload slots the ingest route minted. */
    private static final class PostResult {
        final Outcome outcome;
        @Nullable final String reportId;
        @Nullable final JsonArray attachmentUploads;

        PostResult(Outcome outcome, @Nullable String reportId, @Nullable JsonArray attachmentUploads) {
            this.outcome = outcome;
            this.reportId = reportId;
            this.attachmentUploads = attachmentUploads;
        }

        static PostResult of(Outcome outcome) {
            return new PostResult(outcome, null, null);
        }
    }

    private static PostResult postReport(OkHttpClient client, File file, @Nullable String bearer) {
        byte[] payload;
        try {
            payload = readAll(file);
        } catch (IOException e) {
            // Unreadable file would block the queue forever — quarantine it.
            Log.w(TAG, "flush: unreadable report " + file.getName() + ": " + e.getMessage());
            return PostResult.of(Outcome.PERMANENT_REJECT);
        }

        Request.Builder builder = new Request.Builder()
                .url(Cassette.BASE_URL + "/api/diagnostics/reports")
                .post(RequestBody.create(payload, JSON));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);

        try (Response response = client.newCall(builder.build()).execute()) {
            int code = response.code();
            if (response.isSuccessful()) {
                return parsePostResponse(response);
            }
            if (code == 429 || code >= 500) {
                Log.w(TAG, "flush: transient HTTP " + code + ", will retry " + file.getName());
                return PostResult.of(Outcome.RETRY);
            }
            // 400 invalid_report, 413 payload_too_large, or any other 4xx: retrying
            // the identical body would loop forever. Drop it.
            Log.w(TAG, "flush: permanent HTTP " + code + ", dropping " + file.getName());
            return PostResult.of(Outcome.PERMANENT_REJECT);
        } catch (IOException e) {
            // Network error — keep the file and try again next launch.
            return PostResult.of(Outcome.RETRY);
        }
    }

    /** Parse a 2xx ingest response into {id, attachmentUploads}. A deduped report
     *  returns no attachmentUploads (the first submission already minted them). */
    private static PostResult parsePostResponse(Response response) {
        String reportId = null;
        JsonArray uploads = null;
        try {
            if (response.body() != null) {
                JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
                if (json.has("id") && !json.get("id").isJsonNull()) {
                    reportId = json.get("id").getAsString();
                }
                if (json.has("attachmentUploads") && json.get("attachmentUploads").isJsonArray()) {
                    uploads = json.getAsJsonArray("attachmentUploads");
                }
            }
        } catch (Throwable t) {
            // A malformed 2xx body still counts as accepted — just no attachments.
            Log.w(TAG, "flush: could not parse ingest response: " + t.getMessage());
        }
        return new PostResult(Outcome.SUCCESS, reportId, uploads);
    }

    /** Upload the rolling_trace sidecar (if any) for a just-accepted report. */
    private static void uploadSidecarTrace(OkHttpClient client, File reportFile, PostResult result) {
        File sidecar = sidecarFor(reportFile);
        if (sidecar == null || !sidecar.isFile()) return;
        if (result.attachmentUploads == null) return; // deduped or R2 unconfigured

        String presignedUrl = presignedUrlForSlot(result.attachmentUploads, "rolling_trace");
        if (presignedUrl == null) return;

        byte[] traceBytes;
        try {
            traceBytes = readAll(sidecar);
        } catch (IOException e) {
            Log.w(TAG, "flush: unreadable trace sidecar " + sidecar.getName());
            return;
        }
        uploadAttachment(client, result.reportId, "rolling_trace", TRACE_CONTENT_TYPE, traceBytes, presignedUrl);
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

        // Harvest the OS-recorded trace (ANR stack dump / native tombstone) so it
        // can ship as a rolling_trace R2 attachment. Written to a sidecar next to
        // the report; the flush declares the attachment and runs the two-step.
        byte[] trace = readExitTrace(exit);

        if (trace != null) {
            JsonArray attachments = new JsonArray();
            JsonObject att = new JsonObject();
            att.addProperty("slot", "rolling_trace");
            att.addProperty("kind", isNative ? "native_tombstone" : "anr_trace");
            att.addProperty("content_type", TRACE_CONTENT_TYPE);
            att.addProperty("byte_size", trace.length);
            attachments.add(att);
            body.add("attachments", attachments);
        }

        File file = new File(dir, occurredAt + "-" + idempotencyKey + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "writeExitReport: " + e.getMessage());
            return;
        }

        if (trace != null) {
            File sidecar = new File(dir, occurredAt + "-" + idempotencyKey + TRACE_SIDECAR_SUFFIX);
            try (FileOutputStream out = new FileOutputStream(sidecar)) {
                out.write(trace);
                out.flush();
            } catch (IOException e) {
                // The report still ships; it just won't carry the trace attachment.
                Log.w(TAG, "writeExitReport: trace sidecar write failed: " + e.getMessage());
            }
        }

        prunePending(dir);
    }

    /** Read the exit's trace stream (ANR dump / native tombstone), capped, or null. */
    @Nullable
    @RequiresApi(Build.VERSION_CODES.R)
    private static byte[] readExitTrace(ApplicationExitInfo exit) {
        try (InputStream in = exit.getTraceInputStream()) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < MAX_TRACE_BYTES) {
                out.write(buffer, 0, read);
            }
            return out.size() == 0 ? null : out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "readExitTrace failed: " + t.getMessage());
            return null;
        }
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
    // Manual feedback (report_type=user_feedback) — user-initiated, ALWAYS allowed

    /**
     * Submit an in-app "Report a problem" message to the diagnostics spine as a
     * report_type=user_feedback report. This is USER-INITIATED and is never gated
     * by the diagnostics opt-out — the consent flag only governs auto crash/hang
     * uploads. Runs on a background thread and returns immediately.
     *
     * @param userMessage    the free-text problem description (required, trimmed)
     * @param screenshotPng  an optional PNG snapshot of the current screen,
     *                       attached as slot='screenshot' via the R2 two-step; may
     *                       be null (the message still ships on its own)
     */
    public static void submitUserFeedback(Context context, String userMessage, @Nullable byte[] screenshotPng) {
        if (context != null) appContext = context.getApplicationContext();
        if (userMessage == null) return;
        final String message = truncate(userMessage.trim(), MAX_FEEDBACK_CHARS);
        if (message.isEmpty()) return;
        final byte[] shot = screenshotPng;

        Thread thread = new Thread(() -> {
            try {
                postUserFeedback(message, shot);
            } catch (Throwable t) {
                // Feedback must never crash the app it reports on.
                Log.w(TAG, "submitUserFeedback failed", t);
            }
        }, "cassette-diagnostics-feedback");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void postUserFeedback(String message, @Nullable byte[] screenshotPng) {
        String idempotencyKey = UUID.randomUUID().toString();
        JsonObject body = manualEnvelope("user_feedback", idempotencyKey, System.currentTimeMillis());
        body.addProperty("severity", "info");
        body.addProperty("title", truncate(message, 200));
        body.addProperty("user_message", message);
        body.add("context", crashContext(null)); // carry breadcrumbs for repro context

        boolean hasShot = screenshotPng != null && screenshotPng.length > 0;
        if (hasShot) {
            JsonArray attachments = new JsonArray();
            JsonObject att = new JsonObject();
            att.addProperty("slot", "screenshot");
            att.addProperty("kind", "screenshot");
            att.addProperty("content_type", SCREENSHOT_CONTENT_TYPE);
            att.addProperty("byte_size", screenshotPng.length);
            attachments.add(att);
            body.add("attachments", attachments);
        }

        String bearer = Preferences.getCassetteBearerToken(); // null = anonymous, allowed
        OkHttpClient client = client();

        Request.Builder builder = new Request.Builder()
                .url(Cassette.BASE_URL + "/api/diagnostics/reports")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);

        PostResult result;
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "feedback: ingest HTTP " + response.code());
                return;
            }
            result = parsePostResponse(response);
        } catch (IOException e) {
            Log.w(TAG, "feedback: ingest failed: " + e.getMessage());
            return;
        }

        if (hasShot && result.reportId != null && result.attachmentUploads != null) {
            String presignedUrl = presignedUrlForSlot(result.attachmentUploads, "screenshot");
            if (presignedUrl != null) {
                uploadAttachment(client, result.reportId, "screenshot",
                        SCREENSHOT_CONTENT_TYPE, screenshotPng, presignedUrl);
            }
        }
    }

    // ------------------------------------------------------------------
    // Attachment two-step (presigned R2 PUT → completion callback)

    @Nullable
    private static String presignedUrlForSlot(JsonArray attachmentUploads, String slot) {
        for (JsonElement element : attachmentUploads) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            if (row.has("slot") && slot.equals(row.get("slot").getAsString())
                    && row.has("presignedUrl") && !row.get("presignedUrl").isJsonNull()) {
                return row.get("presignedUrl").getAsString();
            }
        }
        return null;
    }

    /**
     * Second leg of the attachment flow: PUT the payload direct to R2 via the
     * presigned URL, then flip the attachment row 'pending'→'stored' by echoing
     * install_id (the report's owning identity) to the completion callback.
     */
    private static void uploadAttachment(OkHttpClient client, @Nullable String reportId, String slot,
                                         String contentType, byte[] payload, String presignedUrl) {
        if (reportId == null) return;
        MediaType type = MediaType.parse(contentType);
        Request put = new Request.Builder()
                .url(presignedUrl)
                .put(RequestBody.create(payload, type))
                .build();
        try (Response response = client.newCall(put).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "attachment PUT (" + slot + ") HTTP " + response.code());
                return; // row stays 'pending'; no completion callback
            }
        } catch (IOException e) {
            Log.w(TAG, "attachment PUT (" + slot + ") failed: " + e.getMessage());
            return;
        }

        JsonObject complete = new JsonObject();
        complete.addProperty("install_id", Preferences.getCassetteInstallId());
        complete.addProperty("slot", slot);
        complete.addProperty("byte_size", payload.length);
        Request done = new Request.Builder()
                .url(Cassette.BASE_URL + "/api/diagnostics/reports/" + reportId + "/attachments/complete")
                .post(RequestBody.create(complete.toString().getBytes(StandardCharsets.UTF_8), JSON))
                .build();
        try (Response response = client.newCall(done).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "attachment complete (" + slot + ") HTTP " + response.code());
            }
        } catch (IOException e) {
            Log.w(TAG, "attachment complete (" + slot + ") failed: " + e.getMessage());
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
        File[] all = dir.listFiles();
        if (all == null) return;

        // Count report envelopes only — trace sidecars ride with their report and
        // are deleted alongside it, so they must not count toward the cap.
        List<File> reports = new ArrayList<>();
        for (File f : all) {
            if (f.isFile() && f.getName().endsWith(".json")) reports.add(f);
        }
        if (reports.size() <= MAX_PENDING_FILES) return;

        reports.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = reports.size() - MAX_PENDING_FILES;
        for (int i = 0; i < toDelete; i++) {
            deleteReportAndSidecar(reports.get(i));
        }
    }

    /** The trace sidecar path for a report file: {@code <base>.json → <base>.rolling_trace}. */
    @Nullable
    private static File sidecarFor(File reportFile) {
        String name = reportFile.getName();
        if (!name.endsWith(".json")) return null;
        String base = name.substring(0, name.length() - ".json".length());
        return new File(reportFile.getParentFile(), base + TRACE_SIDECAR_SUFFIX);
    }

    /** Delete a report envelope and its trace sidecar (if any), logging failures. */
    private static void deleteReportAndSidecar(File reportFile) {
        if (!reportFile.delete()) {
            Log.w(TAG, "flush: could not delete " + reportFile.getName());
        }
        File sidecar = sidecarFor(reportFile);
        if (sidecar != null && sidecar.isFile() && !sidecar.delete()) {
            Log.w(TAG, "flush: could not delete sidecar " + sidecar.getName());
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
