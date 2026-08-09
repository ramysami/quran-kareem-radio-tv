package com.ramy.quranradiotv

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Holds the ExoPlayer instance and publishes a MediaSession so the TV remote's
 * transport keys (play / pause / stop) and the system now-playing card work even
 * when the Activity is not on screen.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefs: Prefs

    /** Consecutive automatic retries after a stream error; reset once playing. */
    private var retryCount = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(PlayerListener())

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        // The sleep timer stops playback through the service so it works
        // regardless of whether the Activity is alive.
        SleepTimer.onExpire = {
            player.stop()
            player.clearMediaItems()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Nothing is playing and the user swiped the app away — don't linger.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SleepTimer.onExpire = null
        SleepTimer.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private inner class PlayerListener : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retryCount = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            // Live streams drop out; give it a few automatic attempts with
            // backoff before leaving the error visible to the user.
            if (retryCount < MAX_RETRIES) {
                retryCount++
                val delayMs = 1500L * retryCount
                player.postDelayed(delayMs) {
                    player.setMediaItem(buildMediaItem())
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        /**
         * A bare "play" from the remote or the system UI arrives with no media
         * item after a stop — hand back the configured radio stream.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(buildMediaItem()),
                    /* startIndex = */ 0,
                    /* startPositionMs = */ C.TIME_UNSET
                )
            )
        }

        /**
         * Controllers ask for the stream by id rather than shipping a URI, so
         * the current setting is always resolved here at the last moment.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) item else buildMediaItem()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private fun buildMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(prefs.streamUrl)
        .setMediaId(MEDIA_ID)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setMaxPlaybackSpeed(1.02f)
                .build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(getString(R.string.station_name))
                .setArtist(getString(R.string.station_subtitle))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    companion object {
        const val MEDIA_ID = "quran_kareem_radio_live"
        private const val MAX_RETRIES = 5
        private const val USER_AGENT = "QuranKareemRadioTV/1.0 (Android TV)"
    }
}

/** Small helper so retry scheduling reads cleanly against the player's own thread. */
private fun Player.postDelayed(delayMs: Long, action: () -> Unit) {
    val handler = android.os.Handler(applicationLooper)
    handler.postDelayed({ action() }, delayMs)
}
