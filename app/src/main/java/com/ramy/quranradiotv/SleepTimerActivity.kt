package com.ramy.quranradiotv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Nothing but a host for the sleep-timer dialog, opened by the home-screen
 * widget's timer button.
 *
 * A RemoteViews widget cannot show a menu of its own, so this stands in: a
 * windowless activity that draws the app's own preset dialog over whatever the
 * user was looking at and gets out of the way again. The player screen never
 * appears, and with `taskAffinity=""` plus `excludeFromRecents` this never
 * joins — or resurrects — the main app task.
 */
class SleepTimerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A recreated instance (rotation, theme change) would stack a second
        // dialog on top of the first.
        if (savedInstanceState != null) {
            finish()
            return
        }

        SleepTimerDialogs.show(this) { finish() }
    }
}
