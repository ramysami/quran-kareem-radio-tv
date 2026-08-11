package com.ramy.quranradiotv

/**
 * Whether audio is currently playing, or buffering on its way to playing.
 *
 * Published by [PlaybackService] so that whichever screen happens to be showing
 * can hold the TV awake for exactly as long as there is something to listen to.
 * Buffering counts as active on purpose: a stream reconnecting mid-listen must
 * not let the screensaver in.
 */
object PlaybackStatus {

    private val listeners = mutableSetOf<(Boolean) -> Unit>()

    @Volatile
    var isActive: Boolean = false
        private set

    fun set(active: Boolean) {
        if (active == isActive) return
        isActive = active
        // Copy so a listener detaching inside the callback can't break iteration.
        listeners.toList().forEach { it(active) }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isActive)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
