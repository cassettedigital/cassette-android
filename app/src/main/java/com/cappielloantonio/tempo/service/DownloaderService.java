package com.cappielloantonio.tempo.service;

import android.app.Notification;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.NotificationUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.scheduler.PlatformScheduler;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.exoplayer.scheduler.Scheduler;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.util.CassetteSyncStatus;
import com.cappielloantonio.tempo.util.DownloadUtil;

import java.util.List;

@UnstableApi
public class DownloaderService extends androidx.media3.exoplayer.offline.DownloadService {

    private static final int JOB_ID = 1;
    private static final int FOREGROUND_NOTIFICATION_ID = 1;

    public DownloaderService() {
        super(FOREGROUND_NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID, R.string.exo_download_notification_channel_name, 0);
    }

    @NonNull
    @Override
    protected DownloadManager getDownloadManager() {
        DownloadManager downloadManager = DownloadUtil.getDownloadManager(this);
        DownloadNotificationHelper downloadNotificationHelper = DownloadUtil.getDownloadNotificationHelper(this);
        downloadManager.addListener(new TerminalStateNotificationHelper(this, downloadNotificationHelper, FOREGROUND_NOTIFICATION_ID + 1));
        return downloadManager;
    }

    @NonNull
    @Override
    protected Scheduler getScheduler() {
        return new PlatformScheduler(this, JOB_ID);
    }

    /** The "Sync paused" notice, apart from the progress one so stopping the service can't take it away. */
    private static final int PAUSED_NOTIFICATION_ID = FOREGROUND_NOTIFICATION_ID + 100;

    /**
     * cassette (BUG-79): Android 15 and up give a data-sync service 6 hours a day while the app is
     * in the background. At the limit Android calls this and expects the service to stop within
     * seconds; a service that doesn't (Media3 1.5.1 has no answer here) crashes the app, which is
     * what a first sync of a big library would run into. So: pause the downloads (nothing keeps
     * going without a notification), say so, and stop. Opening Cassette picks them up again at once
     * when there is room (CassetteSyncEngine.resumeQueueIfRoom, the sync's own free-space floor),
     * and Android resets the allowance when the app comes to the front. Opening it also clears the
     * notice ({@link #clearPausedNotice}).
     * Nothing already downloaded is touched: Media3 keeps every finished and partial download.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        android.util.Log.w("DownloaderService", "the background download allowance is used up: pausing until Cassette is opened");
        DownloadUtil.getDownloadManager(this).pauseDownloads();
        showPausedNotice(this);
        stopSelf();
    }

    private static void showPausedNotice(Context context) {
        try {
            android.content.Intent open = new android.content.Intent(context, com.cappielloantonio.tempo.ui.activity.MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent tap = android.app.PendingIntent.getActivity(context, 0, open,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notice = new androidx.core.app.NotificationCompat.Builder(context, DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(context.getString(R.string.cassette_sync_paused_title))
                    .setContentText(context.getString(R.string.cassette_sync_paused_text))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build();
            NotificationUtil.setNotification(context, PAUSED_NOTIFICATION_ID, notice);
        } catch (RuntimeException e) {
            // no notice (notifications off): the sync still resumes when Cassette is opened
            android.util.Log.w("DownloaderService", "couldn't show the paused notice: " + e.getMessage());
        }
    }

    /**
     * Opening Cassette clears the "Sync paused" notice (MainActivity.onResume). The queue itself is
     * resumed beside it by CassetteSyncEngine.resumeQueueIfRoom, which keeps it paused while the
     * phone is nearly full.
     */
    public static void clearPausedNotice(Context context) {
        NotificationUtil.setNotification(context, PAUSED_NOTIFICATION_ID, null);
    }

    @NonNull
    @Override
    protected Notification getForegroundNotification(@NonNull List<Download> downloads, @Requirements.RequirementFlags int notMetRequirements) {
        // cassette: media3's stock notification reads "Downloading" with an
        // indeterminate bar — no album, no count, no end. That was the only
        // outward sign a freshly paired phone was doing anything at all, which
        // read as "nothing is happening". Say what is actually going on.
        CassetteSyncStatus.publish();
        CassetteSyncStatus.Snapshot status = CassetteSyncStatus.current();

        String message = status.detail();
        if (message.isEmpty()) {
            message = downloads.size() == 1 ? "1 track" : downloads.size() + " tracks";
        }

        Notification stock = DownloadUtil.getDownloadNotificationHelper(this)
                .buildProgressNotification(this, R.drawable.ic_download, null, message, downloads, notMetRequirements);

        // Recover the builder so the title and a determinate bar can be set
        // without hand-rolling a notification media3 would otherwise own.
        Notification.Builder builder = Notification.Builder.recoverBuilder(this, stock)
                .setContentTitle(status.headline())
                .setContentText(message);

        if (status.tracksTotal > 0 && notMetRequirements == 0) {
            builder.setProgress(status.tracksTotal, status.tracksDone, false);
        }

        return builder.build();
    }

    private static final class TerminalStateNotificationHelper implements DownloadManager.Listener {
        private final Context context;
        private final DownloadNotificationHelper notificationHelper;

        private final Notification successfulDownloadGroupNotification;
        private final Notification failedDownloadGroupNotification;

        private final int successfulDownloadGroupNotificationId;
        private final int failedDownloadGroupNotificationId;

        private int nextNotificationId;

        public TerminalStateNotificationHelper(Context context, DownloadNotificationHelper notificationHelper, int firstNotificationId) {
            this.context = context.getApplicationContext();
            this.notificationHelper = notificationHelper;
            nextNotificationId = firstNotificationId;

            successfulDownloadGroupNotification = DownloadUtil.buildGroupSummaryNotification(
                    this.context,
                    DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                    DownloadUtil.DOWNLOAD_NOTIFICATION_SUCCESSFUL_GROUP,
                    R.drawable.ic_check_circle,
                    "Downloads completed"
            );

            failedDownloadGroupNotification = DownloadUtil.buildGroupSummaryNotification(
                    this.context,
                    DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                    DownloadUtil.DOWNLOAD_NOTIFICATION_FAILED_GROUP,
                    R.drawable.ic_error,
                    "Downloads failed"
            );

            successfulDownloadGroupNotificationId = nextNotificationId++;
            failedDownloadGroupNotificationId = nextNotificationId++;
        }

        @Override
        public void onDownloadChanged(@NonNull DownloadManager downloadManager, Download download, @Nullable Exception finalException) {
            // cassette: keep the tracker's view fresh for every state, so bulk
            // enqueuers can tell queued from missing (completed-only updates
            // left parked downloads invisible → endless re-enqueue).
            DownloaderManager.noteState(download);
            // Keep the in-app banner's counts moving with the queue.
            CassetteSyncStatus.publish();

            Notification notification;

            if (download.state == Download.STATE_COMPLETED) {
                // cassette: no per-song "completed" notification — account sync
                // moves whole albums and would stack one alert per track. The
                // foreground progress notification is the download feedback.
                DownloaderManager.updateRequestDownload(download);
                return;
            } else if (download.state == Download.STATE_FAILED) {
                notification = notificationHelper.buildDownloadFailedNotification(context, R.drawable.ic_error, null, DownloaderManager.getDownloadNotificationMessage(download.request.id));
                notification = Notification.Builder.recoverBuilder(context, notification).setGroup(DownloadUtil.DOWNLOAD_NOTIFICATION_FAILED_GROUP).build();
                NotificationUtil.setNotification(this.context, failedDownloadGroupNotificationId, failedDownloadGroupNotification);
            } else {
                return;
            }

            NotificationUtil.setNotification(context, nextNotificationId++, notification);
        }

        @Override
        public void onDownloadRemoved(@NonNull DownloadManager downloadManager, Download download) {
            DownloaderManager.removeRequestDownload(download);
        }
    }
}
