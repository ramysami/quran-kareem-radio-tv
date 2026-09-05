package com.ramy.quranradiotv

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ramy.quranradiotv.databinding.ActivityMainBinding
import com.ramy.quranradiotv.recognition.ModelStore
import com.ramy.quranradiotv.recognition.Recitation
import com.ramy.quranradiotv.recognition.SurahNames
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Stream URL in force the last time we prepared, so Settings changes can be detected. */
    private var preparedUrl: String? = null

    private var dotAnimator: ObjectAnimator? = null

    private val sleepListener: (Long) -> Unit = { remaining -> renderSleepTimer(remaining) }

    private val playbackListener: (PlaybackStatus.Phase) -> Unit = { phase ->
        keepScreenAwake(phase.isActive)
        renderRecitation()
    }

    private val recitationListener: () -> Unit = {
        renderRecitation()
        // The button was pressed with the microphone chosen and no permission:
        // ask, once per visit, rather than only pointing at the hint.
        if (Recitation.state is Recitation.State.NeedsMicPermission && !micAsked) requestMicrophone()
    }
    private val modelListener: (ModelStore.Status) -> Unit = { renderRecitation() }

    /** Asked for the microphone once this visit; a refusal is not nagged about. */
    private var micAsked = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnStop.setOnClickListener { stopPlayback() }
        binding.btnSleep.setOnClickListener { SleepTimerDialogs.show(this) }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnIdentify.setOnClickListener { Recitation.identify() }
        binding.btnOpenAyah.setOnClickListener { openInQuranApp() }
        addFocusScale(binding.btnOpenAyah)
        binding.recitationHint.setOnClickListener { onRecitationHintTapped() }

        addFocusScale(binding.btnPlayPause)
        addFocusScale(binding.btnStop)
        addFocusScale(binding.btnSleep)
        addFocusScale(binding.btnSettings)
        addFocusScale(binding.btnIdentify)

        binding.btnPlayPause.requestFocus()
        requestNotificationPermissionIfNeeded()
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        connectToService()
        SleepTimer.addListener(sleepListener)
        PlaybackStatus.addListener(playbackListener)
        Recitation.addListener(recitationListener)
        ModelStore.addListener(modelListener)
        // The recogniser works only while this screen is in front: it is the
        // one place its answer is drawn.
        Recitation.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        applyBackground()
        handleStreamUrlChange()
    }

    override fun onStop() {
        Recitation.setForeground(false)
        Recitation.removeListener(recitationListener)
        ModelStore.removeListener(modelListener)
        SleepTimer.removeListener(sleepListener)
        PlaybackStatus.removeListener(playbackListener)
        releaseController()
        super.onStop()
    }

    /**
     * TV only. Holds the display on while there is audio to listen to, and hands
     * the set back to its normal sleep timeout the moment there isn't: otherwise
     * the screensaver appears mid-recitation and the box drops into standby.
     *
     * On a phone this would be actively wrong — locking the screen and carrying
     * on listening is exactly what people want, and playback keeps going there
     * through the foreground service.
     */
    private fun keepScreenAwake(active: Boolean) {
        if (!DeviceType.isTv(this)) return
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- session

    private fun connectToService() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (future.isCancelled) return@addListener
            controller = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            controller?.addListener(playerListener)
            preparedUrl = if ((controller?.mediaItemCount ?: 0) > 0) prefs.streamUrl else null
            renderStatus()
        }, MoreExecutors.directExecutor())
    }

    private fun releaseController() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = renderStatus()
        override fun onPlayerError(error: PlaybackException) = renderStatus()
    }

    // ---------------------------------------------------------------- transport

    private fun startPlayback() {
        val c = controller ?: return
        if (!isNetworkAvailable()) {
            setStatusVisible(true)
            binding.statusText.text = getString(R.string.status_no_network)
            tintDot(R.color.error_red)
            return
        }
        // The URI is resolved service-side from the saved setting; the controller
        // only names the stream, because MediaItem URIs don't survive bundling.
        c.setMediaItem(MediaItem.Builder().setMediaId(PlaybackService.MEDIA_ID).build())
        c.prepare()
        c.play()
        preparedUrl = prefs.streamUrl
        renderStatus()
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE || c.mediaItemCount == 0 -> startPlayback()
            c.playbackState == Player.STATE_ENDED -> startPlayback()
            else -> c.play()
        }
        renderStatus()
    }

    private fun stopPlayback() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        preparedUrl = null
        SleepTimer.cancel()
        renderStatus()
    }

    /** A URL edited in Settings takes effect on the next prepare. */
    private fun handleStreamUrlChange() {
        val c = controller ?: return
        val current = prefs.streamUrl
        if (preparedUrl != null && preparedUrl != current) {
            val wasPlaying = c.isPlaying || c.playWhenReady
            c.stop()
            c.clearMediaItems()
            preparedUrl = null
            if (wasPlaying) startPlayback() else renderStatus()
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun renderStatus() {
        val c = controller
        val playing = c?.isPlaying == true
        val buffering = c?.playbackState == Player.STATE_BUFFERING
        val error = c?.playerError != null

        // Every state below says something worth reading except "Ready", which
        // the Play button already says. Hidden rather than collapsed so the
        // panel doesn't jump the moment playback starts.
        setStatusVisible(true)

        binding.playPauseIcon.setImageResource(
            if (playing || (buffering && c?.playWhenReady == true)) R.drawable.ic_pause
            else R.drawable.ic_play
        )
        binding.playPauseLabel.setText(
            if (playing || (buffering && c?.playWhenReady == true)) R.string.action_pause
            else R.string.action_play
        )

        when {
            error -> {
                binding.statusText.setText(R.string.status_error)
                tintDot(R.color.error_red)
                stopDotPulse()
            }
            buffering -> {
                binding.statusText.setText(
                    if (c?.playWhenReady == true) R.string.status_connecting else R.string.status_buffering
                )
                tintDot(R.color.gold)
                startDotPulse()
            }
            playing -> {
                binding.statusText.setText(R.string.status_playing)
                tintDot(R.color.on_air)
                startDotPulse()
            }
            c != null && c.mediaItemCount > 0 && !c.playWhenReady -> {
                binding.statusText.setText(R.string.status_paused)
                tintDot(R.color.text_secondary)
                stopDotPulse()
            }
            else -> {
                setStatusVisible(false)
                stopDotPulse()
            }
        }
    }

    private fun setStatusVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        binding.statusDot.visibility = visibility
        binding.statusText.visibility = visibility
    }

    private fun renderSleepTimer(remaining: Long) {
        if (remaining <= 0L) {
            binding.sleepStatus.visibility = View.GONE
        } else {
            binding.sleepStatus.visibility = View.VISIBLE
            binding.sleepStatus.text = getString(R.string.sleep_active, SleepTimer.format(remaining))
        }
    }

    // ---------------------------------------------------------------- recitation

    /**
     * Three things: the "Which Surah?" button and what it currently reads, the
     * answer it last produced, and — when there is something to say — a line
     * under them explaining a state the user can do something about.
     */
    private fun renderRecitation() {
        val current = Recitation.current
        val state = Recitation.state
        val enabled = Recitation.isEnabled

        binding.btnIdentify.visibility = if (enabled) View.VISIBLE else View.GONE
        val busy = state is Recitation.State.Loading ||
            state is Recitation.State.Listening ||
            state is Recitation.State.Finishing
        binding.btnIdentify.isEnabled = !busy
        binding.btnIdentify.alpha = if (busy) 0.7f else 1f
        binding.identifyLabel.text = when (state) {
            Recitation.State.Loading -> getString(R.string.action_identify_preparing)
            is Recitation.State.Listening -> getString(R.string.action_identify_listening, Recitation.secondsLeft)
            Recitation.State.Finishing -> getString(R.string.action_identify_finishing)
            else -> getString(R.string.action_identify)
        }

        if (current != null) {
            binding.recitationGroup.visibility = View.VISIBLE
            binding.recitationSurah.text =
                getString(R.string.recitation_surah, SurahNames.name(this, current.surah))
            binding.recitationAyah.text = getString(R.string.recitation_ayah, current.ayah)
        } else {
            binding.recitationGroup.visibility = View.GONE
        }

        val hint: String? = when (state) {
            Recitation.State.Off, Recitation.State.Idle, Recitation.State.Loading,
            Recitation.State.Finishing -> null
            is Recitation.State.Listening ->
                if (state.source == Recitation.Source.MIC) getString(R.string.recitation_hint_listening_mic)
                else getString(R.string.recitation_hint_listening)
            Recitation.State.NotRecognized -> getString(R.string.recitation_hint_not_recognized)
            Recitation.State.NeedsPlayback -> getString(R.string.recitation_hint_needs_playback)
            Recitation.State.ModelMissing -> when (val m = ModelStore.status) {
                is ModelStore.Status.Downloading ->
                    getString(R.string.recitation_hint_downloading, m.percent.coerceAtLeast(0))
                ModelStore.Status.Installing -> getString(R.string.recitation_hint_installing)
                else -> getString(R.string.recitation_hint_model_missing)
            }
            Recitation.State.NeedsMicPermission -> getString(R.string.recitation_hint_mic_permission)
            Recitation.State.MicUnavailable -> getString(R.string.recitation_hint_mic_unavailable)
            Recitation.State.Failed -> getString(R.string.recitation_hint_failed)
        }
        binding.recitationHint.visibility = if (hint == null) View.GONE else View.VISIBLE
        binding.recitationHint.text = hint ?: ""
        binding.recitationHint.isClickable = state is Recitation.State.NeedsMicPermission ||
            state is Recitation.State.ModelMissing
    }

    /**
     * Hands the identified Ayah to the Quran app through its `quran://surah/ayah`
     * link; with no such app installed, the same Ayah on quran.com.
     */
    private fun openInQuranApp() {
        val current = Recitation.current ?: return
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("quran://${current.surah}/${current.ayah}"))
        try {
            startActivity(deepLink)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_quran_failed, Toast.LENGTH_SHORT).show()
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://quran.com/${current.surah}/${current.ayah}")))
            }
        }
    }

    /** The two hints that name something the user can do about it. */
    private fun onRecitationHintTapped() {
        when (Recitation.state) {
            Recitation.State.NeedsMicPermission -> {
                if (Build.VERSION.SDK_INT >= 23 &&
                    micAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                ) {
                    // Refused with "don't ask again": only the system screen can undo that.
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                } else {
                    requestMicrophone()
                }
            }
            Recitation.State.ModelMissing -> openSettings()
            else -> Unit
        }
    }

    private fun requestMicrophone() {
        if (Build.VERSION.SDK_INT < 23) return
        micAsked = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) Recitation.onPermissionResult()
    }

    private fun tintDot(colorRes: Int) {
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun startDotPulse() {
        if (dotAnimator?.isRunning == true) return
        dotAnimator = ObjectAnimator.ofFloat(binding.statusDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopDotPulse() {
        dotAnimator?.cancel()
        dotAnimator = null
        binding.statusDot.alpha = 1f
    }

    /**
     * Grows the focused control. Parents up the chain set clipChildren=false so
     * the extra pixels aren't cut off, and translationZ lifts it above the
     * neighbours it now overlaps.
     */
    private fun addFocusScale(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(140)
                .start()
        }
    }

    // ---------------------------------------------------------------- background

    private fun applyBackground() {
        // Both bundled artworks, portrait and landscape, already carry the
        // wordmark, so our own title would only duplicate it. It comes back for
        // the plain gradient and for a user's own picture.
        //
        // The bias nudge keeps the landscape panel clear of the artwork's logo.
        // In portrait the panel fills its constraints and distributes itself with
        // weighted spacers instead, leaving no slack for a bias to act on, so
        // this call is simply inert there.
        val showTitle = prefs.backgroundMode != Prefs.BG_DEFAULT
        binding.titleGroup.visibility = if (showTitle) View.VISIBLE else View.GONE
        setPanelBias(if (showTitle) 0.5f else 0.68f)

        lifecycleScope.launch {
            when (val result = BackgroundLoader.load(this@MainActivity, prefs)) {
                is BackgroundLoader.Result.UseDefault -> showBackground(null, strongScrim = false)
                is BackgroundLoader.Result.None -> {
                    binding.backgroundImage.setImageDrawable(null)
                    binding.scrim.visibility = View.GONE
                }
                is BackgroundLoader.Result.Custom ->
                    showBackground(BitmapDrawable(resources, result.bitmap), strongScrim = true)
                is BackgroundLoader.Result.Failed -> {
                    showBackground(null, strongScrim = false)
                    Toast.makeText(this@MainActivity, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** A null drawable means the bundled artwork. */
    private fun showBackground(drawable: android.graphics.drawable.Drawable?, strongScrim: Boolean) {
        if (drawable == null) binding.backgroundImage.setImageResource(R.drawable.bg_default)
        else binding.backgroundImage.setImageDrawable(drawable)
        binding.scrim.visibility = View.VISIBLE
        binding.scrim.setBackgroundResource(
            if (strongScrim) R.drawable.scrim_strong else R.drawable.scrim_soft
        )
    }

    private fun setPanelBias(bias: Float) {
        val lp = binding.panel.layoutParams as ConstraintLayout.LayoutParams
        if (lp.verticalBias != bias) {
            lp.verticalBias = bias
            binding.panel.layoutParams = lp
        }
    }

    // ---------------------------------------------------------------- remote keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Volume is deliberately left to the system so the remote's own keys work.
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause(); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (controller?.isPlaying != true) togglePlayPause(); true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (controller?.isPlaying == true) togglePlayPause(); true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopPlayback(); true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                openSettings(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ---------------------------------------------------------------- misc

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private companion object {
        const val REQUEST_MICROPHONE = 1002
    }
}
