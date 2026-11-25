package com.example.fraudcheck.checks

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.widget.EditText
import com.example.fraudcheck.core.Signal
import kotlin.math.sqrt

/**
 * Typing, paste and touch telemetry for onboarding fields.
 * Never reads clipboard contents (Android 12+ shows a toast and it is not needed):
 * a paste/autofill shows up as several characters inserted in a single text change.
 * Only timings and counts are kept, never the typed text.
 */
class BehaviourTracker {
    private val keyTimes = HashMap<String, MutableList<Long>>()
    private val pastes = HashMap<String, Int>()
    private val touchSizes = ArrayList<Float>()
    private val touchPressures = ArrayList<Float>()

    fun watch(field: EditText, name: String) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val inserted = count - before
                if (inserted >= 4) pastes[name] = (pastes[name] ?: 0) + 1
                else if (count == 1 && before == 0) keyTimes.getOrPut(name) { mutableListOf() } += SystemClock.uptimeMillis()
            }
        })
    }

    /** Call from Activity.dispatchTouchEvent. */
    fun onTouch(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && touchSizes.size < 500) {
            touchSizes += ev.size
            touchPressures += ev.pressure
        }
    }

    private fun sd(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    fun pasteSignal(): Signal {
        val total = pastes.values.sum()
        return Signal("behaviour.paste", "Pasted or autofilled into KYC fields", total > 0, weight = 15,
            evidence = "pastesPerField=$pastes (autofill also counts; weigh with other signals)")
    }

    fun typingSignal(): Signal {
        val intervals = keyTimes.values.flatMap { t -> t.zipWithNext { a, b -> (b - a).toDouble() }.filter { it in 20.0..3000.0 } }
        val mean = if (intervals.isEmpty()) 0.0 else intervals.average()
        val spread = sd(intervals)
        // Humans: mean ~150-400 ms with large spread. Scripts: very regular or very fast.
        val robotic = intervals.size >= 8 && (spread < 15.0 || mean < 40.0)
        return Signal("behaviour.typing", "Typing rhythm looks scripted", robotic, weight = 20,
            evidence = "keys=${intervals.size + keyTimes.size} meanGap=%.0fms sd=%.0fms".format(mean, spread))
    }

    fun touchSignal(): Signal {
        val sizes = touchSizes.map { it.toDouble() }
        val pressures = touchPressures.map { it.toDouble() }
        // Many phones report pressure fixed at 1.0, so only flag when BOTH size and pressure never vary.
        val identical = sizes.size >= 5 && sd(sizes) == 0.0 && sd(pressures) == 0.0
        return Signal("behaviour.touch", "Touches identical (injected input)", identical, weight = 15,
            evidence = "touches=${sizes.size} sizeSd=%.4f pressureSd=%.4f".format(sd(sizes), sd(pressures)))
    }
}
