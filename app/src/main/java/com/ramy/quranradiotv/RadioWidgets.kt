package com.ramy.quranradiotv

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Drawing and wiring for both home-screen widgets.
 *
 * The widget has no MediaController — building one takes an async round trip
 * that a BroadcastReceiver has no business waiting on — so it reads
 * [PlaybackStatus] and [SleepTimer] directly. Both live in this same process,
 * and both tell the truth from cold: no process means no [PlaybackService],
 * which means nothing is playing, and the sleep deadline is on disk.
 */
object RadioWidgets {

    /** Play if stopped, pause if playing. Sent by the transport button. */
    const val ACTION_TOGGLE = "com.ramy.quranradiotv.widget.TOGGLE"

    /** Deliberately handled by doing nothing at all. See [noopPendingIntent]. */
    const val ACTION_NOOP = "com.ramy.quranradiotv.widget.NOOP"

    private const val REQUEST_TOGGLE = 3001
    private const val REQUEST_SLEEP = 3002
    private const val REQUEST_NOOP = 3003

    private val LAYOUTS = listOf(
        RadioWidgetProvider::class.java to R.layout.widget_radio,
        RadioWidgetCompactProvider::class.java to R.layout.widget_radio_compact,
    )

    /** Redraws every placed widget. A no-op when the user has placed none. */
    fun refresh(context: Context) {
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
        LAYOUTS.forEach { (provider, layout) ->
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, provider))
            }.getOrNull() ?: return@forEach
            if (ids.isEmpty()) return@forEach
            runCatching { manager.updateAppWidget(ids, build(context, layout)) }
        }
    }

    fun build(context: Context, layoutRes: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layoutRes)
        val phase = PlaybackStatus.phase
        val showPause = phase.isActive

        // The layouts carry a static src so the widget picker's preview isn't
        // a row of empty buttons; at runtime the icons are rasterised here
        // instead, which is both safer than handing a launcher on an old API a
        // vector and how they pick up the current state's tint.
        views.setImageViewBitmap(
            R.id.widget_play_pause,
            icon(
                context,
                if (showPause) R.drawable.ic_pause else R.drawable.ic_play,
                R.color.text_primary
            )
        )
        views.setCharSequence(
            R.id.widget_play_pause,
            "setContentDescription",
            context.getString(if (showPause) R.string.action_pause else R.string.action_play)
        )

        // Idle shows no status line at all, same as the app: "Ready" is the one
        // state the play button already communicates on its own.
        val status = when (phase) {
            PlaybackStatus.Phase.ERROR -> R.string.status_error to R.color.error_red
            PlaybackStatus.Phase.CONNECTING -> R.string.status_connecting to R.color.gold
            PlaybackStatus.Phase.PLAYING -> R.string.status_playing to R.color.on_air
            PlaybackStatus.Phase.PAUSED -> R.string.status_paused to R.color.text_secondary
            PlaybackStatus.Phase.IDLE -> null
        }
        if (status == null) {
            views.setViewVisibility(R.id.widget_dot, View.GONE)
            views.setViewVisibility(R.id.widget_status, View.GONE)
        } else {
            val (statusRes, dotColorRes) = status
            views.setViewVisibility(R.id.widget_dot, View.VISIBLE)
            views.setViewVisibility(R.id.widget_status, View.VISIBLE)
            views.setTextViewText(R.id.widget_status, context.getString(statusRes))
            views.setInt(
                R.id.widget_dot,
                "setColorFilter",
                ContextCompat.getColor(context, dotColorRes)
            )
        }

        val minutes = SleepTimer.remainingMinutes
        val timerOn = minutes > 0
        views.setImageViewBitmap(
            R.id.widget_sleep_icon,
            icon(context, R.drawable.ic_timer, if (timerOn) R.color.gold_bright else R.color.text_primary)
        )
        if (timerOn) {
            views.setTextViewText(
                R.id.widget_sleep_minutes,
                context.getString(R.string.sleep_minutes_short, minutes)
            )
            views.setViewVisibility(R.id.widget_sleep_minutes, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_sleep_minutes, View.GONE)
        }

        views.setOnClickPendingIntent(R.id.widget_play_pause, togglePendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_sleep, sleepPendingIntent(context))

        // Tapping the widget itself is meant to do nothing — but leaving the
        // root without a handler is not how you get that. Pixel Launcher reads
        // an unconsumed tap as "open this app" and fires a MAIN/LAUNCHER intent
        // of its own, so the root has to actively swallow it.
        views.setOnClickPendingIntent(R.id.widget_root, noopPendingIntent(context))

        return views
    }

    // ---------------------------------------------------------------- intents

    private fun togglePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RadioWidgetProvider::class.java).setAction(ACTION_TOGGLE)
        return PendingIntent.getBroadcast(context, REQUEST_TOGGLE, intent, flags())
    }

    private fun sleepPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SleepTimerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, REQUEST_SLEEP, intent, flags())
    }

    private fun noopPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RadioWidgetProvider::class.java).setAction(ACTION_NOOP)
        return PendingIntent.getBroadcast(context, REQUEST_NOOP, intent, flags())
    }

    private fun flags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) f = f or PendingIntent.FLAG_IMMUTABLE
        return f
    }

    /**
     * Starts or stops the radio.
     *
     * The tap on the widget is what earns the app its exemption from Android
     * 12's ban on starting a foreground service from the background, so the
     * play branch is allowed to promote the service here. The pause branch
     * never needs to: something is playing, which means the service is already
     * up in this very process.
     */
    @OptIn(markerClass = [UnstableApi::class])
    fun toggle(context: Context) {
        val intent = Intent(context, PlaybackService::class.java)
        if (PlaybackStatus.isActive) {
            intent.action = PlaybackService.ACTION_PAUSE
            runCatching { context.startService(intent) }
        } else {
            intent.action = PlaybackService.ACTION_PLAY
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }

    // ---------------------------------------------------------------- icons

    private fun icon(context: Context, drawableRes: Int, colorRes: Int): Bitmap? {
        val drawable = AppCompatResources.getDrawable(context, drawableRes)?.mutate() ?: return null
        val tinted = DrawableCompat.wrap(drawable)
        DrawableCompat.setTint(tinted, ContextCompat.getColor(context, colorRes))

        val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        tinted.setBounds(0, 0, size, size)
        tinted.draw(Canvas(bitmap))
        return bitmap
    }

    /** Rasterise generously; the ImageViews scale down, and widgets get resized. */
    private const val ICON_DP = 48
}

/**
 * Shared behaviour for both widget sizes. Subclasses differ only in which
 * layout they draw.
 */
abstract class RadioWidgetProviderBase : AppWidgetProvider() {

    protected abstract val layoutRes: Int

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetManager.updateAppWidget(appWidgetIds, RadioWidgets.build(context, layoutRes))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RadioWidgets.ACTION_TOGGLE) {
            RadioWidgets.toggle(context)
            // Playback state changes asynchronously and will push its own
            // update; this one is just so the button doesn't look dead.
            RadioWidgets.refresh(context)
        }
        super.onReceive(context, intent)
    }
}

/** The 4x2 widget: transport, station, status, sleep timer. */
class RadioWidgetProvider : RadioWidgetProviderBase() {
    override val layoutRes = R.layout.widget_radio
}

/** The one-row widget: transport, status, sleep timer. */
class RadioWidgetCompactProvider : RadioWidgetProviderBase() {
    override val layoutRes = R.layout.widget_radio_compact
}
