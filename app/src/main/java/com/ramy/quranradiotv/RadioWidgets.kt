package com.ramy.quranradiotv

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.SizeF
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

    /**
     * Redraws every placed widget. A no-op when the user has placed none.
     *
     * One at a time rather than all at once, because the backdrop is cut to the
     * shape of the particular widget it goes behind, and two widgets of the same
     * kind can be sitting at different sizes.
     */
    fun refresh(context: Context) {
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
        LAYOUTS.forEach { (provider, layout) ->
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, provider))
            }.getOrNull() ?: return@forEach
            ids.forEach { id ->
                runCatching { manager.updateAppWidget(id, build(context, layout, sizeOf(manager, id, layout))) }
            }
        }
    }

    /**
     * What the launcher says this widget currently occupies, in dp. Portrait
     * takes the narrower width and the taller height, which is the shape the
     * widget spends most of its life in; a launcher that declines to say falls
     * back to the size declared for it.
     */
    fun sizeOf(manager: AppWidgetManager, appWidgetId: Int, layoutRes: Int): SizeF {
        val fallback = if (layoutRes == R.layout.widget_radio_compact) COMPACT_SIZE else STANDARD_SIZE
        val options: Bundle = runCatching {
            manager.getAppWidgetOptions(appWidgetId)
        }.getOrNull() ?: return fallback

        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return if (width > 0 && height > 0) SizeF(width.toFloat(), height.toFloat()) else fallback
    }

    fun build(context: Context, layoutRes: Int, sizeDp: SizeF): RemoteViews {
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

        // The painting, or the plain panel the layout already draws behind it.
        val backdrop = runCatching { backdrop(context, sizeDp) }.getOrNull()
        if (backdrop == null) {
            views.setViewVisibility(R.id.widget_backdrop, View.GONE)
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg)
        } else {
            views.setImageViewBitmap(R.id.widget_backdrop, backdrop)
            views.setViewVisibility(R.id.widget_backdrop, View.VISIBLE)
            // The bitmap carries the frame itself, so the drawable underneath
            // would only draw a second stroke a hair outside the first.
            views.setInt(R.id.widget_root, "setBackgroundResource", 0)
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

    // ---------------------------------------------------------------- backdrop

    /**
     * The widget's whole backdrop as a single bitmap: the app's painting,
     * cropped to the widget's shape, under the scrim that keeps gold-on-navy
     * text readable over a picture, inside the rounded frame.
     *
     * One bitmap rather than a stack of views because RemoteViews cannot clip an
     * image to rounded corners before API 31, and a square picture behind a
     * rounded frame shows its corners at every one of them.
     *
     * Drawn at a fraction of the widget's real size and stretched back up on the
     * way in. A soft painting survives that easily, and it matters: RemoteViews
     * cross to the launcher over binder, which refuses a payload beyond a
     * generous but real limit, and a widget that trips it draws nothing at all.
     *
     * Null when the user asked for no background image, which leaves the plain
     * panel the layout draws on its own — the same answer the app gives.
     */
    private fun backdrop(context: Context, sizeDp: SizeF): Bitmap? {
        if (Prefs(context).backgroundMode == Prefs.BG_NONE) return null

        val density = context.resources.displayMetrics.density
        val fullWidthPx = sizeDp.width * density
        if (fullWidthPx < 1f || sizeDp.height < 1f) return null

        val scale = minOf(1f, MAX_BACKDROP_PX / fullWidthPx)
        val width = (fullWidthPx * scale).toInt().coerceAtLeast(1)
        val height = (sizeDp.height * density * scale).toInt().coerceAtLeast(1)

        val key = "${width}x$height"
        cachedBackdrop?.let { if (key == cachedKey && !it.isRecycled) return it }

        val art = decodeArtwork(context, width, height) ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = cornerRadiusDp(context) * density * scale
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Centre-crop, as an ImageView would, but expressed as a shader so the
        // rounded corners come out of the same anti-aliased draw as the picture.
        val cover = maxOf(width / art.width.toFloat(), height / art.height.toFloat())
        paint.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(cover, cover)
                    postTranslate(
                        (width - art.width * cover) / 2f,
                        (height - art.height * cover) / 2f
                    )
                }
            )
        }
        canvas.drawRoundRect(bounds, radius, radius, paint)

        paint.shader = null
        paint.color = ContextCompat.getColor(context, R.color.widget_scrim)
        canvas.drawRoundRect(bounds, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * scale
        paint.color = ContextCompat.getColor(context, R.color.panel_stroke)
        val inset = paint.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius,
            radius,
            paint
        )

        art.recycle()
        cachedBackdrop = bitmap
        cachedKey = key
        return bitmap
    }

    /**
     * The landscape painting, deliberately, rather than the bg_default alias:
     * the alias would hand a phone the portrait one, and a widget is a wide,
     * shallow strip that would show little more than a slice down its middle.
     */
    private fun decodeArtwork(context: Context, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, R.drawable.bg_artwork_landscape, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // no alpha in a painting; halves the memory
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.bg_artwork_landscape, options)
    }

    /** Android 12 launchers have their own opinion; widget_bg follows it too. */
    private fun cornerRadiusDp(context: Context): Float =
        if (Build.VERSION.SDK_INT >= 31) {
            context.resources.getDimension(android.R.dimen.system_app_widget_background_radius) /
                context.resources.displayMetrics.density
        } else {
            18f
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

    /**
     * The widest the backdrop is drawn, whatever the widget's real size. Keeps
     * what crosses to the launcher comfortably inside what binder will carry.
     */
    private const val MAX_BACKDROP_PX = 600f

    /** Fallbacks matching the sizes the two widgets declare to the launcher. */
    private val STANDARD_SIZE = SizeF(250f, 110f)
    private val COMPACT_SIZE = SizeF(180f, 50f)

    /** The backdrop survives between updates; only its size and mode change it. */
    private var cachedBackdrop: Bitmap? = null
    private var cachedKey: String? = null

    /** Dropped when the background setting changes, so the next draw rebuilds. */
    fun clearBackdropCache() {
        cachedBackdrop = null
        cachedKey = null
    }
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
        // One at a time: the backdrop is cut to the shape of each widget, and
        // two of the same kind can be sitting at different sizes.
        appWidgetIds.forEach { id ->
            val size = RadioWidgets.sizeOf(appWidgetManager, id, layoutRes)
            appWidgetManager.updateAppWidget(id, RadioWidgets.build(context, layoutRes, size))
        }
    }

    /**
     * Resizing changes the shape the backdrop has to be cut to, and the
     * launcher tells us about it here rather than through onUpdate.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val size = RadioWidgets.sizeOf(appWidgetManager, appWidgetId, layoutRes)
        runCatching {
            appWidgetManager.updateAppWidget(appWidgetId, RadioWidgets.build(context, layoutRes, size))
        }
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
