package com.narezany.myvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Держит процесс живым, пока поднят туннель, и даёт кнопку «Отключить» в шторке.
 */
public class KeepAliveService extends Service {

    public static final String ACTION_START = "com.narezany.myvpn.START";
    public static final String ACTION_STOP = "com.narezany.myvpn.STOP";

    private static final String TAG = "MyVpn/KeepAlive";
    private static final String CHANNEL_ID = "vpn_status";
    private static final int NOTIFICATION_ID = 42;

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        final String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            VpnManager.get(getApplicationContext()).disconnect(null);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (final Exception e) {
            Log.w(TAG, "Не удалось показать уведомление", e);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        final Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent open = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final Intent stopIntent = new Intent(this, KeepAliveService.class);
        stopIntent.setAction(ACTION_STOP);
        final PendingIntent stop = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.notif_stop), stop).build());
        return builder.build();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
