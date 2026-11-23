package com.example.arrowhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/** Accessibility service used only to send taps (dispatchGesture). */
class TapService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    var isRunning = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancel()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Taps each point in order with [delayMs] between taps.
     * [onProgress] gets the index about to be tapped; [onFinished] gets true if all taps went through.
     * Touching the screen yourself cancels the run.
     */
    fun tapSequence(
        points: List<PointF>,
        delayMs: Long,
        onProgress: (Int) -> Unit,
        onFinished: (Boolean) -> Unit,
    ) {
        cancel()
        val my = ++token
        isRunning = true

        fun step(i: Int) {
            if (my != token) return
            if (i >= points.size) { isRunning = false; onFinished(true); return }
            onProgress(i)
            tap(points[i]) { ok ->
                main.post {
                    if (my != token) return@post
                    if (!ok) { isRunning = false; onFinished(false) }
                    else main.postDelayed({ step(i + 1) }, delayMs)
                }
            }
        }
        step(0)
    }

    fun cancel() {
        token++
        isRunning = false
        main.removeCallbacksAndMessages(null)
    }

    private fun tap(p: PointF, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(p.x, p.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = done(true)
            override fun onCancelled(g: GestureDescription?) = done(false)
        }, null)
        if (!sent) done(false)
    }
}
