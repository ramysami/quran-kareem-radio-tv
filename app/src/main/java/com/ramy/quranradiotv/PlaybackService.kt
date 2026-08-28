package com.ramy.quranradiotv

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
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

    /** The player as the session publishes it. See [LivePlayer]. */
    private lateinit var livePlayer: LivePlayer
    private lateinit var prefs: Prefs

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Consecutive automatic retries after a stream error; reset once playing. */
    private var retryCount = 0

    /** Whether the stand-in notification, rather than Media3's, is on screen. */
    private var placeholderVisible = false

    /** The phase the stand-in currently reads, so it is redrawn only on change. */
    private var placeholderPhase: PlaybackStatus.Phase? = null

    /** Whether [idleShutdown] is armed, and whether it has already fired. */
    private var idleStopPending = false
    private var stopping = false

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

        livePlayer = LivePlayer(player)
        setMediaNotificationProvider(LiveNotificationProvider())

        val session = MediaSession.Builder(this, livePlayer)
            .setCallback(SessionCallback())
            .setSessionActivity(contentIntent())
            .setCustomLayout(customLayout())
            .build()
        mediaSession = session

        // Media3 only draws the transport notification for sessions it has been
        // told about, and it is told about them when a MediaController connects.
        // Started from the widget nothing ever connects, so without this the
        // radio would play with no media controls anywhere — and the stand-in
        // notification below would be left sitting there in their place.
        addSession(session)

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
            stopRadio()
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

    // ---------------------------------------------------------------- notification

    /**
     * startForegroundService() is a promise to call startForeground() within
     * five seconds, and the system kills the app for breaking it.
     *
     * Media3 posts its own notification only once its controller has connected
     * to the session and the stream has begun to buffer, and a live stream on a
     * slow connection can take longer than that — so claim the foreground up
     * front with a stand-in. It carries Media3's own channel and notification
     * id, so the real notification replaces this one rather than sitting beside
     * it, and until that happens [refreshPlaceholder] keeps its text honest.
     */
    private fun claimForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        placeholderVisible = true
        placeholderPhase = PlaybackStatus.Phase.CONNECTING
        postPlaceholder(R.string.status_connecting)
    }

    /**
     * Media3 is about to post the real transport notification over the top of
     * the stand-in's id. From here on the shade is its business, not ours.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        placeholderVisible = false
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    /**
     * Keeps the stand-in's text in step with the player for as long as it is the
     * notification on screen. Without it, a stand-in that Media3 hasn't yet
     * replaced goes on reading "Connecting…" over audio that is already playing
     * — and goes on reading it after a stream that never arrived at all.
     */
    private fun refreshPlaceholder(phase: PlaybackStatus.Phase) {
        if (!placeholderVisible || phase == placeholderPhase) return
        placeholderPhase = phase
        when (phase) {
            PlaybackStatus.Phase.CONNECTING -> postPlaceholder(R.string.status_connecting)
            PlaybackStatus.Phase.PLAYING -> postPlaceholder(R.string.status_playing)
            PlaybackStatus.Phase.PAUSED -> postPlaceholder(R.string.status_paused)
            PlaybackStatus.Phase.ERROR -> postPlaceholder(R.string.status_error)
            PlaybackStatus.Phase.IDLE -> dropPlaceholder()
        }
    }

    /** startForeground() with an id already in the shade updates it in place. */
    private fun postPlaceholder(@StringRes textRes: Int) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                placeholder(textRes),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        }
    }

    private fun dropPlaceholder() {
        if (!placeholderVisible) return
        placeholderVisible = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    private fun placeholder(@StringRes textRes: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.station_name))
            .setContentText(getString(textRes))
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // ---------------------------------------------------------------- controls

    /**
     * The two controls Media3 has no notion of, in the order they are shown.
     *
     * They have to be custom actions rather than ordinary transport commands
     * because of how Android 13 and later draw the panel in the shade: it lays
     * out five fixed slots of its own — play/pause, previous, next, and two for
     * whatever else the session offers — and ignores the buttons in our
     * notification entirely. Stop is not one of the slots it knows, so the only
     * way to put stop and a sleep timer in front of the user is to offer them
     * as the session's own custom actions and let them fill the two free slots
     * that dropping previous and next leaves behind.
     */
    private fun customLayout(): List<CommandButton> = listOf(
        commandButton(COMMAND_STOP, R.drawable.ic_stop, R.string.action_stop, slot = 1),
        commandButton(COMMAND_SLEEP_TIMER, R.drawable.ic_timer, R.string.action_sleep_timer, slot = 2),
    )

    private fun commandButton(
        action: String,
        @DrawableRes iconRes: Int,
        @StringRes nameRes: Int,
        slot: Int
    ): CommandButton = CommandButton.Builder()
        .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
        .setIconResId(iconRes)
        .setDisplayName(getString(nameRes))
        .setEnabled(true)
        .setExtras(compactSlot(slot))
        .build()

    /** Which of the three slots the collapsed notification draws a button in. */
    private fun compactSlot(index: Int): Bundle = Bundle().apply {
        putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, index)
    }

    /**
     * The notification Media3 draws for Android 12 and below, and for the
     * television. From 13 the system builds its own from the session and this
     * goes unseen, so the two have to be arranged separately to arrive at the
     * same three controls.
     *
     * Only the compact view needs saying: left alone, the base class reserves
     * its three collapsed slots for previous, play/pause and next, and a stream
     * with no previous or next would collapse to a single button with stop and
     * the timer hidden until the user expanded the notification.
     */
    private inner class LiveNotificationProvider :
        DefaultMediaNotificationProvider(this@PlaybackService) {

        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean
        ): ImmutableList<CommandButton> {
            val buttons = ImmutableList.Builder<CommandButton>()
            if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE)) {
                buttons.add(
                    commandButton(
                        Player.COMMAND_PLAY_PAUSE,
                        if (showPauseButton) R.drawable.ic_pause else R.drawable.ic_play,
                        if (showPauseButton) R.string.action_pause else R.string.action_play
                    )
                )
            }
            // Stop and the sleep timer, already carrying slots 1 and 2.
            return buttons.addAll(customLayout).build()
        }

        private fun commandButton(
            playerCommand: Int,
            @DrawableRes iconRes: Int,
            @StringRes nameRes: Int
        ): CommandButton = CommandButton.Builder()
            .setPlayerCommand(playerCommand)
            .setIconResId(iconRes)
            .setDisplayName(getString(nameRes))
            .setEnabled(true)
            .setExtras(compactSlot(0))
            .build()
    }

    /**
     * Stop, as the app's own Stop button means it: the stream is dropped rather
     * than held, and a sleep timer counting down towards a radio that is no
     * longer playing is cancelled with it.
     */
    private fun stopRadio() {
        player.stop()
        player.clearMediaItems()
        SleepTimer.cancel()
    }

    /**
     * The sleep-timer button opens the same preset dialog the widget does, over
     * whatever the user was looking at, without the app coming up behind it.
     *
     * Starting it from here is a background activity launch, which Android
     * allows only under [OverlayPermission] — or while the app happens to have a
     * screen of its own in front, which is checked so that a user who never
     * granted anything still gets the dialog in the case where it can work.
     * Without either, there is nothing to show and no way to say so on screen,
     * so the request for the permission goes to the shade instead.
     */
    private fun openSleepTimer() {
        if (!OverlayPermission.isGranted(this) && !isAppInForeground()) {
            OverlayPermission.prompt(this)
            return
        }
        OverlayPermission.dismissPrompt(this)
        val intent = Intent(this, SleepTimerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { startActivity(intent) }
    }

    /**
     * Whether one of the app's own screens is in front of the user.
     *
     * A foreground service on its own reports IMPORTANCE_FOREGROUND_SERVICE,
     * which ranks below this, so the radio merely playing is not mistaken for
     * the app being open.
     */
    private fun isAppInForeground(): Boolean {
        val manager = getSystemService(ActivityManager::class.java) ?: return false
        val mine = android.os.Process.myPid()
        return runCatching {
            manager.runningAppProcesses?.any {
                it.pid == mine &&
                    it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- idle shutdown

    /**
     * A radio left paused has nothing to come back to — this is a live stream,
     * so there is no position being held for anyone. Rather than sit in the
     * shade holding a process open for the rest of the day, give both back.
     */
    private val idleShutdown = Runnable {
        idleStopPending = false
        if (PlaybackStatus.isActive) return@Runnable
        stopping = true
        player.stop()
        player.clearMediaItems()
        dropPlaceholder()
        // Harmless while the Activity still has a controller bound: the service
        // lives on as a bound one until that goes, with nothing playing.
        stopSelf()
    }

    private fun scheduleIdleShutdown(phase: PlaybackStatus.Phase) {
        if (phase.isActive) {
            mainHandler.removeCallbacks(idleShutdown)
            idleStopPending = false
            stopping = false
            return
        }
        if (stopping || idleStopPending) return
        idleStopPending = true
        mainHandler.postDelayed(idleShutdown, IDLE_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- playback

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
        mainHandler.removeCallbacks(idleShutdown)
        runCatching { unregisterReceiver(screenOffReceiver) }
        // Media3 hands its notification back only when playback stops, and a
        // paused one it has already detached from the foreground outlives the
        // service entirely. With the player gone that is a dead card in the
        // shade, so take it down here whichever of the two it is.
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this)
                .cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        }
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
            val phase = when {
                player.playerError != null -> PlaybackStatus.Phase.ERROR
                !player.playWhenReady && player.mediaItemCount > 0 -> PlaybackStatus.Phase.PAUSED
                player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.Phase.CONNECTING
                player.playbackState == Player.STATE_READY -> PlaybackStatus.Phase.PLAYING
                else -> PlaybackStatus.Phase.IDLE
            }
            PlaybackStatus.set(phase)
            refreshPlaceholder(phase)
            scheduleIdleShutdown(phase)
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
         * Grants every controller the two custom actions, and withholds the
         * seek and skip commands from all of them.
         *
         * The withholding is what the system's media panel reads: it builds its
         * buttons from the commands the session advertises, so a stream that
         * cannot be skipped through is a stream drawn without skip buttons.
         * [LivePlayer] says the same thing from the player's side, and both are
         * needed — one is what the notification is built from, the other is
         * what each connecting controller is granted.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .removeAll(*LivePlayer.SEEK_COMMANDS)
                        .build()
                )
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(COMMAND_STOP, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_SLEEP_TIMER, Bundle.EMPTY))
                        .build()
                )
                .build()

        /** Where the stop and sleep-timer buttons in the shade arrive. */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val result = when (customCommand.customAction) {
                COMMAND_STOP -> { stopRadio(); SessionResult.RESULT_SUCCESS }
                COMMAND_SLEEP_TIMER -> { openSleepTimer(); SessionResult.RESULT_SUCCESS }
                else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            return Futures.immediateFuture(SessionResult(result))
        }

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
                // Dresses the card in the shade in the same picture the app
                // opens on, and it is the picture the system draws the card's
                // own colours from. A URI rather than a bitmap because the
                // system loads and scales it in its own time.
                .setArtworkUri(BackgroundLoader.artworkUri(this@PlaybackService, prefs))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    companion object {
        const val MEDIA_ID = "quran_kareem_radio_live"

        const val ACTION_PLAY = "com.ramy.quranradiotv.action.PLAY"
        const val ACTION_PAUSE = "com.ramy.quranradiotv.action.PAUSE"

        /** Custom session commands, for the two buttons Media3 has no name for. */
        private const val COMMAND_STOP = "com.ramy.quranradiotv.command.STOP"
        private const val COMMAND_SLEEP_TIMER = "com.ramy.quranradiotv.command.SLEEP_TIMER"
        private const val MAX_RETRIES = 5
        private const val USER_AGENT = "QuranKareemRadioTV/1.0 (Android TV)"

        /** How long a paused or stopped radio is kept resident before giving up. */
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        private const val CHANNEL_ID = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
    }
}

/** Small helper so retry scheduling reads cleanly against the player's own thread. */
private fun Player.postDelayed(delayMs: Long, action: () -> Unit) {
    val handler = android.os.Handler(applicationLooper)
    handler.postDelayed({ action() }, delayMs)
}
