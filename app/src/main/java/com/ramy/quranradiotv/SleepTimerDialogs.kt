package com.ramy.quranradiotv

import android.app.Activity
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * The sleep-timer preset list, shared by the player screen and by
 * [SleepTimerActivity] — the bare activity the home-screen widget opens.
 *
 * Keeping one copy is the point: the widget offers exactly the durations the
 * app does, in the same order, in the same words, in both languages.
 */
object SleepTimerDialogs {

    private val PRESET_MINUTES = listOf(
        R.string.sleep_5 to 5,
        R.string.sleep_15 to 15,
        R.string.sleep_30 to 30,
        R.string.sleep_60 to 60,
    )

    /**
     * [onDone] runs once the user is finished — after a duration is picked, or
     * the dialog is cancelled. Choosing "Custom…" hands off to a second dialog
     * and defers [onDone] until that one closes, so a caller that finishes
     * itself on [onDone] doesn't vanish mid-flow.
     */
    fun show(activity: Activity, onDone: () -> Unit = {}) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        var chaining = false

        if (SleepTimer.isActive) {
            labels += activity.getString(R.string.sleep_off)
            actions += {
                SleepTimer.cancel()
                toast(activity, activity.getString(R.string.sleep_cancelled))
            }
        }

        PRESET_MINUTES.forEach { (labelRes, minutes) ->
            labels += activity.getString(labelRes)
            actions += { setSleepTimer(activity, minutes) }
        }

        labels += activity.getString(R.string.sleep_custom)
        actions += {
            chaining = true
            showCustom(activity, onDone)
        }

        AlertDialog.Builder(activity, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.sleep_dialog_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
            .apply { setOnDismissListener { if (!chaining) onDone() } }
            .show()
    }

    private fun showCustom(activity: Activity, onDone: () -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = activity.getString(R.string.sleep_custom_hint)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_muted))
        }
        val container = FrameLayout(activity).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        fun commit() {
            val minutes = input.text.toString().trim().toIntOrNull()
            if (minutes == null || minutes !in 1..600) toast(activity, activity.getString(R.string.sleep_invalid))
            else setSleepTimer(activity, minutes)
        }

        val dialog = AlertDialog.Builder(activity, R.style.Theme_QuranRadio_Dialog)
            .setTitle(R.string.sleep_custom_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> commit() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        dialog.setOnDismissListener { onDone() }

        // The TV keypad swallows D-pad focus, so its own "done" key confirms
        // rather than making the user back out and walk over to OK.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                dialog.dismiss()
                true
            } else false
        }

        dialog.show()
        input.requestFocus()
    }

    private fun setSleepTimer(activity: Activity, minutes: Int) {
        SleepTimer.start(minutes)
        toast(
            activity,
            activity.getString(
                R.string.sleep_set,
                activity.getString(R.string.sleep_minutes_value, minutes)
            )
        )
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
