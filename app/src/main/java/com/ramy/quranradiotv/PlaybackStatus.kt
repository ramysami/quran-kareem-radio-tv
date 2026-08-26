package com.ramy.quranradiotv

/**
 * What the player is currently doing.
 *
 * Published by [PlaybackService] so that whichever screen happens to be showing
 * can hold the TV awake for exactly as long as there is something to listen to,
 * and so the home-screen widget has a status to draw — the widget has no
 * MediaController of its own to ask.
 *
 * Reading this from a cold process gives [Phase.IDLE], which is the truth: no
 * process means no [PlaybackService], which means nothing is playing.
 */
object PlaybackStatus {

    enum class Phase {
        IDLE,
        CONNECTING,
        PLAYING,
        PAUSED,
        ERROR;

        /**
         * Whether there is audio to listen to, or audio on its way. Buffering
         * counts on purpose: a stream reconnecting mid-listen must not let the
         * TV screensaver in.
         */
        val isActive: Boolean
            get() = this == PLAYING || this == CONNECTING
    }

    private val listeners = mutableSetOf<(Phase) -> Unit>()

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    val isActive: Boolean
        get() = phase.isActive

    fun set(phase: Phase) {
        if (phase == this.phase) return
        this.phase = phase
        // Copy so a listener detaching inside the callback can't break iteration.
        listeners.toList().forEach { it(phase) }
    }

    fun addListener(listener: (Phase) -> Unit) {
        listeners.add(listener)
        listener(phase)
    }

    fun removeListener(listener: (Phase) -> Unit) {
        listeners.remove(listener)
    }
}
