package com.ramy.quranradiotv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * "Display over other apps" — held for the sleep-timer button in the media
 * notification, and for nothing else in the app.
 *
 * Android will not let a backgrounded app start an activity, and a press on a
 * media-control button arrives as a session callback rather than as a
 * PendingIntent sent by the launcher, which is what earns the widget's timer
 * button its start. Holding this permission is one of the handful of exemptions
 * from that rule, and the only one an ordinary app can qualify for: the system
 * logs the resulting launch as BAL_ALLOW_SAW_PERMISSION.
 *
 * Nothing is ever actually drawn over another app. The permission is held for
 * the exemption alone, and the radio works without it — the timer button is the
 * one thing that goes quiet.
 */
object OverlayPermission {

    private const val CHANNEL_ID = "setup_channel_id"
    private const val NOTIFICATION_ID = 1002
    private const val REQUEST = 3004

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    /** The system screen where it is turned on. Absent on some televisions. */
    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    /**
     * Asks for it from the one place a backgrounded app can still reach the
     * user: the shade they already have open, since that is where the button
     * they just pressed lives.
     *
     * Tapping this is an ordinary notification tap, so the system opens the
     * settings screen on our behalf and draws the shade closed on the way —
     * which is the very thing the timer button itself is not permitted to do.
     */
    fun prompt(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.setup_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val tap = PendingIntent.getActivity(context, REQUEST, settingsIntent(context), flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.overlay_prompt_title))
            .setContentText(context.getString(R.string.overlay_prompt_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.overlay_prompt_text))
            )
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** Taken down once the permission is in place, so it can't be tapped stale. */
    fun dismissPrompt(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
