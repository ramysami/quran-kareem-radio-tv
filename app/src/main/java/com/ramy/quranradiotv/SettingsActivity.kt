package com.ramy.quranradiotv

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ramy.quranradiotv.databinding.ActivitySettingsBinding
import com.ramy.quranradiotv.databinding.ItemSettingBinding
import com.ramy.quranradiotv.recognition.ModelStore
import com.ramy.quranradiotv.recognition.Recitation
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) applyCustomBackground(uri)
    }

    /** Settings can be left open mid-recitation, so it holds the TV awake too. */
    private val playbackListener: (PlaybackStatus.Phase) -> Unit = { phase ->
        if (DeviceType.isTv(this)) {
            if (phase.isActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        bindRows()
        render()
        binding.rowStreamEdit.root.requestFocus()
    }

    /** The model download reports progress here while the screen is open. */
    private val modelListener: (ModelStore.Status) -> Unit = { renderRecognition() }

    override fun onStart() {
        super.onStart()
        PlaybackStatus.addListener(playbackListener)
        ModelStore.addListener(modelListener)
    }

    /** The overlay permission is granted on a system screen, so re-read it here. */
    override fun onResume() {
        super.onResume()
        render()
        if (OverlayPermission.isGranted(this)) OverlayPermission.dismissPrompt(this)
    }

    override fun onStop() {
        ModelStore.removeListener(modelListener)
        PlaybackStatus.removeListener(playbackListener)
        super.onStop()
    }

    // ---------------------------------------------------------------- rows

    private fun bindRows() {
        binding.rowStreamEdit.root.setOnClickListener { showStreamUrlDialog() }

        binding.rowStreamReset.root.setOnClickListener {
            prefs.resetStreamUrl()
            render()
            toast(getString(R.string.saved))
        }

        binding.rowBgDefault.root.setOnClickListener {
            prefs.backgroundMode = Prefs.BG_DEFAULT
            backgroundChanged()
        }

        binding.rowBgNone.root.setOnClickListener {
            prefs.backgroundMode = Prefs.BG_NONE
            backgroundChanged()
        }

        binding.rowBgCustom.root.setOnClickListener { showCustomBackgroundDialog() }

        binding.rowBgReset.root.setOnClickListener {
            prefs.resetBackground()
            BackgroundLoader.clearCache(this)
            backgroundChanged()
            toast(getString(R.string.saved))
        }

        // Absent from the television layout: a set has no shade to press the
        // timer button in, and generally no screen for the permission either.
        binding.rowSleepOverlay?.root?.setOnClickListener { openOverlaySettings() }

        binding.rowRecognitionEnable.root.setOnClickListener { toggleRecognition() }
        binding.rowRecognitionSourceRadio.root.setOnClickListener {
            prefs.recognitionSource = Prefs.SOURCE_RADIO
            recognitionChanged()
        }
        binding.rowRecognitionSourceMic.root.setOnClickListener {
            prefs.recognitionSource = Prefs.SOURCE_MIC
            recognitionChanged()
            requestMicrophoneIfNeeded()
        }
        binding.rowRecognitionHold.root.setOnClickListener { showHoldDialog() }
        binding.rowRecognitionModel.root.setOnClickListener { onModelRowTapped() }

        binding.rowResetAll.root.setOnClickListener { confirmResetAll() }

        listOfNotNull(
            binding.rowStreamEdit, binding.rowStreamReset,
            binding.rowBgDefault, binding.rowBgNone, binding.rowBgCustom, binding.rowBgReset,
            binding.rowSleepOverlay,
            binding.rowRecognitionEnable, binding.rowRecognitionSourceRadio,
            binding.rowRecognitionSourceMic, binding.rowRecognitionHold, binding.rowRecognitionModel,
            binding.rowResetAll
        ).forEach { row ->
            row.root.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.02f else 1f)
                    .scaleY(if (hasFocus) 1.02f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(120)
                    .start()
            }
        }
    }

    /**
     * The home-screen widgets draw the same background choice, so a change here
     * has to reach them too — they otherwise sit unchanged until playback next
     * moves.
     */
    private fun backgroundChanged() {
        RadioWidgets.clearBackdropCache()
        RadioWidgets.refresh(this)
        render()
    }

    private fun render() {
        bindRow(
            binding.rowStreamEdit,
            title = getString(R.string.settings_stream_edit),
            summary = prefs.streamUrl
        )
        bindRow(
            binding.rowStreamReset,
            title = getString(R.string.settings_stream_reset),
            summary = if (prefs.isStreamUrlDefault) getString(R.string.settings_using_default)
            else getString(R.string.settings_using_custom)
        )

        val mode = prefs.backgroundMode
        bindRow(
            binding.rowBgDefault,
            title = getString(R.string.settings_bg_default),
            summary = getString(R.string.settings_bg_default_summary),
            checked = mode == Prefs.BG_DEFAULT
        )
        bindRow(
            binding.rowBgNone,
            title = getString(R.string.settings_bg_none),
            summary = getString(R.string.settings_bg_none_summary),
            checked = mode == Prefs.BG_NONE
        )
        bindRow(
            binding.rowBgCustom,
            title = getString(R.string.settings_bg_custom),
            summary = if (mode == Prefs.BG_CUSTOM && prefs.customBackgroundUri != null)
                prefs.customBackgroundUri!!
            else getString(R.string.settings_bg_custom_summary),
            checked = mode == Prefs.BG_CUSTOM
        )
        bindRow(
            binding.rowBgReset,
            title = getString(R.string.settings_bg_reset)
        )

        binding.rowSleepOverlay?.let { row ->
            val granted = OverlayPermission.isGranted(this)
            bindRow(
                row,
                title = getString(R.string.settings_overlay_title),
                summary = getString(
                    if (granted) R.string.settings_overlay_on
                    else R.string.settings_overlay_off
                ),
                checked = granted
            )
        }

        renderRecognition()

        bindRow(
            binding.rowResetAll,
            title = getString(R.string.settings_reset_all),
            summary = getString(R.string.settings_reset_all_summary)
        )

        applyBackgroundPreview()
    }

    // ---------------------------------------------------------------- recognition

    private fun renderRecognition() {
        val supported = Recitation.isSupported
        val enabled = supported && prefs.recognitionEnabled
        val installed = ModelStore.isInstalled(this)
        val status = ModelStore.status

        bindRow(
            binding.rowRecognitionEnable,
            title = getString(R.string.settings_recognition_enable),
            summary = when {
                !supported -> getString(R.string.settings_recognition_unsupported)
                !enabled -> getString(R.string.settings_recognition_off, size(ModelStore.DOWNLOAD_BYTES))
                installed -> getString(R.string.settings_recognition_on)
                else -> getString(R.string.settings_recognition_on_no_model)
            },
            toggle = if (supported) enabled else null,
            enabled = supported
        )

        val source = prefs.recognitionSource
        bindRow(
            binding.rowRecognitionSourceRadio,
            title = getString(R.string.settings_recognition_source_radio),
            summary = getString(R.string.settings_recognition_source_radio_summary),
            checked = source == Prefs.SOURCE_RADIO,
            enabled = enabled
        )
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        bindRow(
            binding.rowRecognitionSourceMic,
            title = getString(R.string.settings_recognition_source_mic),
            summary = if (source == Prefs.SOURCE_MIC && !micGranted)
                getString(R.string.settings_recognition_source_mic_denied)
            else getString(R.string.settings_recognition_source_mic_summary),
            checked = source == Prefs.SOURCE_MIC,
            enabled = enabled
        )

        bindRow(
            binding.rowRecognitionHold,
            title = getString(R.string.settings_recognition_hold),
            summary = getString(R.string.settings_recognition_hold_summary, holdLabel(prefs.recognitionHoldMillis)),
            enabled = enabled
        )

        bindRow(
            binding.rowRecognitionModel,
            title = getString(R.string.settings_recognition_model),
            summary = when (status) {
                is ModelStore.Status.Installed ->
                    getString(R.string.model_status_installed, size(status.bytesOnDisk))
                is ModelStore.Status.Downloading ->
                    if (status.percent >= 0) getString(R.string.model_status_downloading, status.percent)
                    else getString(R.string.model_status_downloading_unknown)
                ModelStore.Status.Installing -> getString(R.string.model_status_installing)
                is ModelStore.Status.Failed -> getString(R.string.model_status_failed, status.reason)
                ModelStore.Status.Missing ->
                    getString(R.string.model_status_missing, size(ModelStore.DOWNLOAD_BYTES))
            },
            checked = status is ModelStore.Status.Installed,
            progress = when (status) {
                is ModelStore.Status.Downloading -> status.percent
                ModelStore.Status.Installing -> -1
                else -> null
            }
        )
    }

    /**
     * Turning it on with no model yet offers the download at once — it is the
     * one thing the feature cannot do without, and the size is worth saying
     * before it starts.
     */
    private fun toggleRecognition() {
        if (!Recitation.isSupported) return
        val turningOn = !prefs.recognitionEnabled
        prefs.recognitionEnabled = turningOn
        recognitionChanged()
        if (turningOn) {
            if (!ModelStore.isInstalled(this) && ModelStore.status is ModelStore.Status.Missing) {
                confirmModelDownload()
            }
            if (prefs.recognitionSource == Prefs.SOURCE_MIC) requestMicrophoneIfNeeded()
        }
    }

    private fun recognitionChanged() {
        Recitation.onSettingsChanged()
        renderRecognition()
    }

    private fun onModelRowTapped() {
        when (val status = ModelStore.status) {
            is ModelStore.Status.Installed -> confirmModelDelete(status.bytesOnDisk)
            is ModelStore.Status.Downloading -> confirmModelCancel()
            ModelStore.Status.Installing -> Unit
            is ModelStore.Status.Failed, ModelStore.Status.Missing -> confirmModelDownload()
        }
    }

    private fun confirmModelDownload() {
        AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.model_confirm_download_title)
            .setMessage(getString(R.string.model_confirm_download_text, size(ModelStore.DOWNLOAD_BYTES)))
            .setPositiveButton(R.string.dialog_download) { _, _ ->
                ModelStore.download()
                toast(getString(R.string.model_download_started))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmModelDelete(bytes: Long) {
        AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.model_confirm_delete_title)
            .setMessage(getString(R.string.model_confirm_delete_text, size(bytes)))
            .setPositiveButton(R.string.dialog_remove) { _, _ -> ModelStore.delete() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmModelCancel() {
        AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.model_confirm_cancel_title)
            .setPositiveButton(R.string.dialog_stop) { _, _ -> ModelStore.cancelDownload() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showHoldDialog() {
        val labels = HOLD_PRESETS.map { (labelRes, _) -> getString(labelRes) }.toTypedArray()
        val current = HOLD_PRESETS.indexOfFirst { it.second == prefs.recognitionHoldMillis }
        AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.settings_recognition_hold)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.recognitionHoldMillis = HOLD_PRESETS[which].second
                renderRecognition()
                toast(getString(R.string.saved))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun holdLabel(millis: Long): String {
        HOLD_PRESETS.firstOrNull { it.second == millis }?.let { return getString(it.first) }
        return getString(R.string.sleep_minutes_value, (millis / 60_000L).toInt().coerceAtLeast(1))
    }

    private fun requestMicrophoneIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            Recitation.onPermissionResult()
            renderRecognition()
        }
    }

    private fun size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

    /**
     * Hands off to the system's own permission screen. Televisions generally
     * have none, and there is no shade there to press the timer button in, so a
     * missing screen is stated plainly rather than treated as a failure.
     */
    private fun openOverlaySettings() {
        try {
            startActivity(OverlayPermission.settingsIntent(this))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.settings_overlay_unavailable))
        }
    }

    /**
     * [checked] draws the tick and the selected fill, for rows that are one of
     * a set. [toggle] draws a switch instead, for rows that are simply on or
     * off. [progress] shows a bar (0–100, or -1 for indeterminate). A row that
     * is not [enabled] is dimmed and ignores presses: a setting that has no
     * effect until another is turned on.
     */
    private fun bindRow(
        row: ItemSettingBinding,
        title: String,
        summary: String? = null,
        checked: Boolean = false,
        toggle: Boolean? = null,
        progress: Int? = null,
        enabled: Boolean = true
    ) {
        row.title.text = title
        if (summary.isNullOrBlank()) {
            row.summary.visibility = View.GONE
        } else {
            row.summary.visibility = View.VISIBLE
            row.summary.text = summary
        }
        if (toggle != null) {
            row.toggle.visibility = View.VISIBLE
            row.toggle.isChecked = toggle
            row.check.visibility = View.GONE
        } else {
            row.toggle.visibility = View.GONE
            row.check.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        }
        if (progress != null) {
            row.progress.visibility = View.VISIBLE
            row.progress.isIndeterminate = progress < 0
            if (progress >= 0) row.progress.progress = progress
        } else {
            row.progress.visibility = View.GONE
        }
        row.root.isSelected = checked
        row.root.isEnabled = enabled
        row.root.isFocusable = enabled
        row.root.alpha = if (enabled) 1f else 0.45f
    }

    /**
     * The screen shows the chosen background live, so a pick can be judged before
     * leaving. The dense settings list always gets the strong scrim — unlike the
     * player, it covers the whole left column.
     */
    private fun applyBackgroundPreview() {
        lifecycleScope.launch {
            val result = BackgroundLoader.load(this@SettingsActivity, prefs)
            when (result) {
                is BackgroundLoader.Result.UseDefault ->
                    binding.backgroundImage.setImageResource(R.drawable.bg_default)
                is BackgroundLoader.Result.None ->
                    binding.backgroundImage.setImageDrawable(null)
                is BackgroundLoader.Result.Custom ->
                    binding.backgroundImage.setImageDrawable(BitmapDrawable(resources, result.bitmap))
                is BackgroundLoader.Result.Failed -> {
                    binding.backgroundImage.setImageResource(R.drawable.bg_default)
                    toast(result.reason)
                }
            }
            binding.scrim.visibility =
                if (result is BackgroundLoader.Result.None) View.GONE else View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- stream URL

    private fun showStreamUrlDialog() {
        val input = textInput(prefs.streamUrl, R.string.settings_stream_hint, InputType.TYPE_TEXT_VARIATION_URI)

        fun commit() {
            val url = input.text.toString().trim()
            if (!isValidUrl(url)) {
                toast(getString(R.string.url_invalid))
            } else {
                prefs.streamUrl = url
                render()
                toast(getString(R.string.saved))
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.settings_stream_dialog_title)
            .setView(wrap(input))
            .setPositiveButton(R.string.dialog_save) { _, _ -> commit() }
            .setNeutralButton(R.string.dialog_reset) { _, _ ->
                prefs.resetStreamUrl()
                render()
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        input.onDone { commit(); dialog.dismiss() }
        dialog.show()
        input.requestFocus()
        input.setSelection(input.text.length)
    }

    // ---------------------------------------------------------------- background

    private fun showCustomBackgroundDialog() {
        val labels = arrayOf(
            getString(R.string.custom_bg_browse),
            getString(R.string.custom_bg_url)
        )

        AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.custom_bg_dialog_title)
            .setItems(labels) { _, which ->
                if (which == 0) launchFilePicker() else showImageUrlDialog()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun launchFilePicker() {
        try {
            pickImage.launch(arrayOf("image/*"))
        } catch (e: ActivityNotFoundException) {
            // Plenty of TV boxes ship without a document provider UI.
            toast(getString(R.string.custom_bg_no_picker))
            showImageUrlDialog()
        }
    }

    private fun showImageUrlDialog() {
        val existing = prefs.customBackgroundUri?.takeIf { it.startsWith("http") } ?: ""
        val input = textInput(existing, R.string.custom_bg_url_hint, InputType.TYPE_TEXT_VARIATION_URI)

        fun commit() {
            val url = input.text.toString().trim()
            if (!isValidUrl(url)) {
                toast(getString(R.string.url_invalid))
            } else {
                BackgroundLoader.clearCache(this)
                prefs.customBackgroundUri = url
                prefs.backgroundMode = Prefs.BG_CUSTOM
                backgroundChanged()
                toast(getString(R.string.custom_bg_applied))
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.custom_bg_url_title)
            .setView(wrap(input))
            .setPositiveButton(R.string.dialog_save) { _, _ -> commit() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        input.onDone { commit(); dialog.dismiss() }
        dialog.show()
        input.requestFocus()
    }

    private fun applyCustomBackground(uri: Uri) {
        // Without this the URI stops resolving after a reboot.
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers don't offer persistable grants; the URI may still
                // work for this install, and load() reports it if it stops.
            }
        }
        prefs.customBackgroundUri = uri.toString()
        prefs.backgroundMode = Prefs.BG_CUSTOM
        backgroundChanged()
        toast(getString(R.string.custom_bg_applied))
    }

    // ---------------------------------------------------------------- reset

    private fun confirmResetAll() {
        AlertDialog.Builder(this, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.settings_reset_all)
            .setMessage(R.string.settings_reset_all_confirm)
            .setPositiveButton(R.string.dialog_reset) { _, _ ->
                prefs.resetAll()
                BackgroundLoader.clearCache(this)
                // Recognition goes back to off; the model itself is kept, since
                // it is a download rather than a setting, and its row says how
                // to remove it.
                Recitation.onSettingsChanged()
                render()
                toast(getString(R.string.settings_reset_done))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun textInput(initial: String, hintRes: Int, variation: Int): EditText =
        EditText(this).apply {
            setText(initial)
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or variation
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_muted))
        }

    /**
     * The TV's on-screen keyboard takes over D-pad focus, so its "done" key is
     * the natural way to confirm — otherwise the user has to back out of the
     * keyboard and walk across to the Save button.
     */
    private fun EditText.onDone(action: () -> Unit) {
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                action()
                true
            } else false
        }
    }

    private fun wrap(view: View): FrameLayout = FrameLayout(this).apply {
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(view)
    }

    private fun isValidUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_MICROPHONE = 1002

        /** How long an identified Surah stays up, in the order offered. */
        val HOLD_PRESETS = listOf(
            R.string.hold_30s to 30_000L,
            R.string.hold_1m to 60_000L,
            R.string.hold_2m to 2 * 60_000L,
            R.string.hold_5m to 5 * 60_000L,
            R.string.hold_15m to 15 * 60_000L,
        )
    }
}
