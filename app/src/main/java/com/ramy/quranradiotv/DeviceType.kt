package com.ramy.quranradiotv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * Phones and TVs want opposite power behaviour, so a few decisions turn on this.
 *
 * A TV is a fixed appliance with no battery: it should stay lit while the radio
 * plays, because the alternative is the screensaver cutting in and dropping the
 * box into standby. A phone is the reverse — locking it and carrying on
 * listening is the whole point, and pinning the display on would flatten the
 * battery.
 */
object DeviceType {

    fun isTv(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        // Some cheaper boxes don't report the television UI mode; the leanback
        // feature is the reliable fallback.
        return context.packageManager.hasSystemFeature("android.software.leanback")
    }
}
