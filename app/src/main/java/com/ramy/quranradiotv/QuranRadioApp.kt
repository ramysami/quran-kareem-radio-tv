package com.ramy.quranradiotv

import android.app.Application
import com.ramy.quranradiotv.recognition.ModelStore
import com.ramy.quranradiotv.recognition.Recitation

/**
 * The one component guaranteed to be alive whichever way the process was
 * started — an Activity, the service, or a bare widget broadcast.
 *
 * That makes it the right place to restore a persisted sleep timer, and to hold
 * the listeners that keep the home-screen widget in step with playback.
 */
class QuranRadioApp : Application() {

    /** Minute currently drawn on the widget, so a 1 Hz tick isn't 1 Hz of IPC. */
    private var shownMinutes = -1

    override fun onCreate() {
        super.onCreate()

        SleepTimer.init(this)
        ModelStore.init(this)
        Recitation.init(this)

        PlaybackStatus.addListener {
            RadioWidgets.refresh(this)
        }

        SleepTimer.addListener { remaining ->
            val minutes = ((remaining + 59_999L) / 60_000L).toInt()
            if (minutes != shownMinutes) {
                shownMinutes = minutes
                RadioWidgets.refresh(this)
            }
        }
    }
}
