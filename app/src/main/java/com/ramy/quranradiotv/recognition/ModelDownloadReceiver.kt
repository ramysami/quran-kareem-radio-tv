package com.ramy.quranradiotv.recognition

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Hears the system finish the model download — possibly long after the user
 * left the app — and hands over to [ModelStore] to unpack it.
 *
 * The id is checked against the one we asked for; the broadcast is protected,
 * so nobody else can send it, but a download of something else entirely is
 * still none of our business.
 */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        ModelStore.init(context)
        ModelStore.onDownloadComplete(id)
    }
}
