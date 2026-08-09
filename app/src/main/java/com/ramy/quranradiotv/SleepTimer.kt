package com.ramy.quranradiotv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Process-wide sleep timer. Lives outside the Activity so it keeps counting
 * while the user is in Settings or has left the app, and fires even if the
 * player UI is gone.
 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())

    /** Elapsed-realtime instant at which playback should stop, or 0 when idle. */
    private var deadline: Long = 0L

    /** Set by [PlaybackService] — what to do when the timer runs out. */
    var onExpire: (() -> Unit)? = null

    private val listeners = mutableSetOf<(Long) -> Unit>()

    private val tick = object : Runnable {
        override fun run() {
            if (!isActive) return
            val left = remainingMillis
            if (left <= 0L) {
                deadline = 0L
                notifyListeners(0L)
                onExpire?.invoke()
                return
            }
            notifyListeners(left)
            handler.postDelayed(this, 1000L)
        }
    }

    val isActive: Boolean
        get() = deadline > 0L

    val remainingMillis: Long
        get() = if (deadline == 0L) 0L else (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        notifyListeners(remainingMillis)
        handler.postDelayed(tick, 1000L)
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        deadline = 0L
        notifyListeners(0L)
    }

    fun addListener(l: (Long) -> Unit) {
        listeners.add(l)
        l(remainingMillis)
    }

    fun removeListener(l: (Long) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners(remaining: Long) {
        // Copy so a listener removing itself during the callback can't break iteration.
        listeners.toList().forEach { it(remaining) }
    }

    /** "1:05:00" or "24:30" — omits the hour part when there is none. */
    fun format(millis: Long): String {
        val totalSeconds = (millis + 999L) / 1000L // round up so it never shows 0:00 while running
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
