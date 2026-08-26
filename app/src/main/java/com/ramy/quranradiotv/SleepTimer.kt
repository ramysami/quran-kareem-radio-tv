package com.ramy.quranradiotv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Process-wide sleep timer. Lives outside the Activity so it keeps counting
 * while the user is in Settings or has left the app, and fires even if the
 * player UI is gone.
 *
 * The deadline is persisted, because the home-screen widget has to be able to
 * draw the timer from a process that was started for nothing but an
 * `APPWIDGET_UPDATE` broadcast — and because an armed timer being silently
 * forgotten when the process dies is worse than it firing late.
 *
 * Two things drive it. The [handler] tick runs once a second while the process
 * is alive and is what the in-app "Stops in 4:59" line reads; it is also what
 * stops playback punctually, since a playing service holds a wake lock and so
 * keeps the (uptime-based) handler honest. The alarm in [scheduleAlarm] is the
 * safety net: it survives the process, wakes the widget when the displayed
 * minute changes, and guarantees expiry.
 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var prefs: Prefs? = null

    /** Wall-clock instant at which playback should stop, or 0 when idle. */
    private var deadline: Long = 0L

    /** Set by [PlaybackService] — what to do when the timer runs out. */
    var onExpire: (() -> Unit)? = null

    private val listeners = mutableSetOf<(Long) -> Unit>()

    private val tick = object : Runnable {
        override fun run() {
            if (!isActive) return
            val left = remainingMillis
            if (left <= 0L) {
                expire()
                return
            }
            notifyListeners(left)
            handler.postDelayed(this, 1000L)
        }
    }

    /**
     * Called from [QuranRadioApp] before anything else touches the timer, so a
     * deadline written by a previous process is picked up whichever component
     * happened to start this one.
     */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = Prefs(app)

        val stored = prefs?.sleepDeadline ?: 0L
        if (stored <= 0L) return

        if (stored <= System.currentTimeMillis()) {
            // Ran out while there was no process to act on it. Nothing can have
            // been playing, so there is nothing to stop — just tidy up.
            clearDeadline()
        } else {
            deadline = stored
            handler.postDelayed(tick, 1000L)
            scheduleAlarm()
        }
    }

    val isActive: Boolean
        get() = deadline > 0L

    val remainingMillis: Long
        get() = if (deadline == 0L) 0L else (deadline - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Whole minutes left, rounded up, so it only reads 0 once the timer is done. */
    val remainingMinutes: Int
        get() = ceilMinutes(remainingMillis)

    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        deadline = System.currentTimeMillis() + minutes * 60_000L
        prefs?.sleepDeadline = deadline
        notifyListeners(remainingMillis)
        handler.postDelayed(tick, 1000L)
        scheduleAlarm()
    }

    fun cancel() {
        clearDeadline()
    }

    /** Driven by [SleepAlarmReceiver]: either the timer is up, or it is time to redraw. */
    fun onAlarm() {
        if (!isActive) return
        if (remainingMillis <= 0L) expire() else scheduleAlarm()
    }

    fun addListener(l: (Long) -> Unit) {
        listeners.add(l)
        l(remainingMillis)
    }

    fun removeListener(l: (Long) -> Unit) {
        listeners.remove(l)
    }

    private fun expire() {
        // Reachable from both the handler tick and the alarm, so it has to be
        // safe to arrive here twice.
        if (deadline == 0L) return
        clearDeadline()
        onExpire?.invoke()
    }

    private fun clearDeadline() {
        handler.removeCallbacks(tick)
        deadline = 0L
        prefs?.sleepDeadline = 0L
        cancelAlarm()
        notifyListeners(0L)
    }

    private fun notifyListeners(remaining: Long) {
        // Copy so a listener removing itself during the callback can't break iteration.
        listeners.toList().forEach { it(remaining) }
    }

    // ---------------------------------------------------------------- alarm

    /**
     * Schedules the next wake-up: either the moment the widget's minute badge
     * would change, or the deadline itself, whichever comes first.
     *
     * Deliberately inexact. `setExactAndAllowWhileIdle` would drag in the
     * `SCHEDULE_EXACT_ALARM` permission for a timer that is already kept
     * punctual by the handler whenever it actually matters.
     */
    private fun scheduleAlarm() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val shown = ceilMinutes(remainingMillis)
        val at = (deadline - (shown - 1).coerceAtLeast(0) * 60_000L).coerceAtMost(deadline)
        val pi = alarmIntent(ctx) ?: return

        if (at >= deadline && Build.VERSION.SDK_INT >= 23) {
            // The one that actually stops the radio, so let it through Doze.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // A redraw nobody is watching if the device is asleep. Let it wait.
            am.set(AlarmManager.RTC, at, pi)
        }
    }

    private fun cancelAlarm() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmIntent(ctx)?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    private fun alarmIntent(ctx: Context): PendingIntent? {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            ctx,
            REQUEST_ALARM,
            Intent(ctx, SleepAlarmReceiver::class.java),
            flags
        )
    }

    // ---------------------------------------------------------------- format

    private fun ceilMinutes(millis: Long): Int = ((millis + 59_999L) / 60_000L).toInt()

    /** "1:05:00" or "24:30" — omits the hour part when there is none. */
    fun format(millis: Long): String {
        val totalSeconds = (millis + 999L) / 1000L // round up so it never shows 0:00 while running
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private const val REQUEST_ALARM = 2001
}
