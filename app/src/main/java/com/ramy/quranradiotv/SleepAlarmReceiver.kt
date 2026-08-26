package com.ramy.quranradiotv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the sleep timer when the process would otherwise be asleep or gone.
 *
 * Fires once per displayed minute so the widget's badge stays honest, and once
 * more on the deadline itself to stop playback. Both cases end in a widget
 * refresh — including the case where [SleepTimer.init] has already tidied away
 * a deadline that ran out while nothing was running.
 */
class SleepAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        SleepTimer.onAlarm()
        RadioWidgets.refresh(context)
    }
}
