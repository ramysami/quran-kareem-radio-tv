package com.ramy.quranradiotv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import androidx.media3.session.DefaultMediaNotificationProvider
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

        // TV only. There, the screen is held on for as long as the radio plays,
        // so the display going off can only mean a deliberate press of the power
        // button — and a live stream left running in standby just burns data on
        // audio nobody can hear (measured at 12 unbroken minutes of it).
        //
        // A phone is the opposite: the screen goes off constantly, and stopping
        // playback there would break listening with the handset locked.
        if (DeviceType.isTv(this)) {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (player.mediaItemCount == 0) return

            player.stop()
            player.clearMediaItems()
            SleepTimer.cancel()
        }
    }

    /**
     * The home-screen widget's transport buttons land here.
     *
     * Everything unrecognised is handed straight to Media3, because this is also
     * where `ACTION_MEDIA_BUTTON` arrives — the play command a car head unit
     * sends on connect, which is what revives the radio after the process has
     * been killed.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                claimForeground()
                startRadio()
            }
            ACTION_PAUSE -> player.pause()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * startForegroundService() is a promise to call startForeground() within
     * five seconds, and the system kills the app for breaking it.
     *
     * Media3 posts its notification only once playback is genuinely under way,
     * and a live stream on a slow connection takes longer than that to buffer —
     * so claim the foreground up front with a placeholder. It carries Media3's
     * own channel and notification id, so its real notification replaces this
     * one rather than sitting beside it.
     */
    private fun claimForeground() {
        val channelId = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID

        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val placeholder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.station_name))
            .setContentText(getString(R.string.status_connecting))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        runCatching {
            ServiceCompat.startForeground(
                this,
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                placeholder,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        }
    }

    /**
     * Starts the stream from cold, reprepared if the player has nothing loaded.
     * Playing right away is also what settles the foreground-service obligation:
     * Media3 promotes the service the moment `playWhenReady` goes true.
     */
    private fun startRadio() {
        val needsPrepare = player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        if (needsPrepare) {
            player.setMediaItem(buildMediaItem())
            player.prepare()
        }
        player.play()
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
        runCatching { unregisterReceiver(screenOffReceiver) }
        PlaybackStatus.set(PlaybackStatus.Phase.IDLE)
        // The timer itself is deliberately left armed: it outlives the service
        // now, so a timer set from the widget still applies when the radio is
        // started again. Only the callback goes, since there is no longer a
        // player for it to stop.
        SleepTimer.onExpire = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private inner class PlayerListener : Player.Listener {

        override fun onEvents(player: Player, events: Player.Events) {
            // Buffering with intent to play reports as CONNECTING rather than
            // idle, so a mid-listen reconnect doesn't hand the TV back to the
            // screensaver — and so the widget says "Connecting…" instead of
            // looking like nothing happened.
            PlaybackStatus.set(
                when {
                    player.playerError != null -> PlaybackStatus.Phase.ERROR
                    !player.playWhenReady && player.mediaItemCount > 0 -> PlaybackStatus.Phase.PAUSED
                    player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.Phase.CONNECTING
                    player.playbackState == Player.STATE_READY -> PlaybackStatus.Phase.PLAYING
                    else -> PlaybackStatus.Phase.IDLE
                }
            )
        }

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

        const val ACTION_PLAY = "com.ramy.quranradiotv.action.PLAY"
        const val ACTION_PAUSE = "com.ramy.quranradiotv.action.PAUSE"
        private const val MAX_RETRIES = 5
        private const val USER_AGENT = "QuranKareemRadioTV/1.0 (Android TV)"
    }
}

/** Small helper so retry scheduling reads cleanly against the player's own thread. */
private fun Player.postDelayed(delayMs: Long, action: () -> Unit) {
    val handler = android.os.Handler(applicationLooper)
    handler.postDelayed({ action() }, delayMs)
}
