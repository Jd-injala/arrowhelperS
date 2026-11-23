package com.example.arrowhelper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.Executors

/** Runs the floating toolbar, grabs screenshots, and shows hints / drives auto-taps. */
class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1
        private val AUTO_TOKEN = Any()
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var capturer: ScreenCapturer? = null
    private var bar: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var gridBtn: Button? = null
    private var autoBtn: Button? = null
    private var hintView: HintView? = null
    private var editor: GridEditorView? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground() // must happen before getMediaProjection on Android 14+
        if (capturer == null) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            capturer = try {
                ScreenCapturer(this, code, data) { main.post { stopSelf() } }
            } catch (e: Exception) {
                toast("Couldn't start screen capture: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (bar == null) showBar()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        autoRunning = false
        TapService.instance?.cancel()
        listOf(hintView, editor, bar).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        hintView = null; editor = null; bar = null
        capturer?.release()
        capturer = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- toolbar ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBar() {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD222222.toInt())
            val p = (4 * d).toInt()
            setPadding(p, p, p, p)
        }
        val handle = TextView(this).apply {
            text = "⠿"; textSize = 22f; setTextColor(Color.WHITE)
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
        row.addView(handle)
        gridBtn = barButton(row, "Grid") { toggleEditor() }
        barButton(row, "Hint") { onHint() }
        autoBtn = barButton(row, "Auto") { onAuto() }
        barButton(row, "✕") { stopSelf() }

        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = (80 * d).toInt()
        }
        handle.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - downX).toInt()
                        params.y = startY + (e.rawY - downY).toInt()
                        wm.updateViewLayout(row, params)
                    }
                }
                return true
            }
        })
        wm.addView(row, params)
        bar = row
        barParams = params
    }

    private fun barButton(row: LinearLayout, label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick() }
            row.addView(this)
        }

    private fun overlayParams(size: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+ only lets touches pass through overlays that are at most 80% opaque.
            if (!touchable) alpha = 0.8f
        }
    }

    /** Keep the toolbar above any overlay added after it. */
    private fun raiseBar() {
        val b = bar ?: return
        val p = barParams ?: return
        wm.removeView(b)
        wm.addView(b, p)
    }

    // ---------- grid setup ----------

    private fun toggleEditor() {
        val ed = editor
        if (ed == null) {
            clearHints()
            val v = GridEditorView(this, Prefs.gridRect(this))
            wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, touchable = true))
            editor = v
            raiseBar()
            gridBtn?.text = "Save"
            toast("Drag the corners so the box covers the whole puzzle (all the dots), then tap Save")
        } else {
            ed.screenRect()?.let { Prefs.setGridRect(this, it) }
            wm.removeView(ed)
            editor = null
            gridBtn?.text = "Grid"
            toast("Grid saved")
        }
    }

    // ---------- hint / auto ----------

    private fun onHint() {
        if (hintView != null) { clearHints(); return }
        analyze { board, result ->
            showHints(board, result)
            report(board, result)
        }
    }

    private var autoRunning = false
    private var lastCount = -1
    private var noProgress = 0
    private var autoTaps = 0

    private fun onAuto() {
        val tapper = TapService.instance
        if (tapper == null) {
            toast("Turn on Arrow Helper in Accessibility settings to use Auto")
            return
        }
        if (autoRunning) { stopAuto("Stopped"); return }
        autoRunning = true
        lastCount = -1; noProgress = 0; autoTaps = 0
        autoBtn?.text = "Stop"
        autoStep()
    }

    private fun stopAuto(msg: String) {
        autoRunning = false
        TapService.instance?.cancel()
        main.removeCallbacksAndMessages(AUTO_TOKEN)
        autoBtn?.text = "Auto"
        clearHints()
        toast(msg)
    }

    /**
     * One round: fresh screenshot → read board → tap ONE free arrow → wait → repeat.
     * Re-reading every time copes with the board moving/zooming and with one-off misreads.
     */
    private fun autoStep() {
        if (!autoRunning) return
        val tapper = TapService.instance ?: return stopAuto("Auto-tap was turned off")
        analyze { board, result ->
            if (!autoRunning) return@analyze
            val count = board.arrows.size
            if (count == 0) return@analyze stopAuto(if (autoTaps > 0) "Done — $autoTaps taps" else "No arrows found")
            if (result.order.isEmpty()) {
                showHints(board, result)
                return@analyze stopAuto("Every arrow left looks blocked (red). Send a screenshot of this.")
            }
            // Safety: if taps aren't removing arrows, stop instead of losing lives.
            if (lastCount != -1 && count >= lastCount) noProgress++ else noProgress = 0
            lastCount = count
            if (noProgress >= 2) {
                showHints(board, result)
                return@analyze stopAuto("Taps aren't removing arrows — stopped. Send a screenshot of this.")
            }
            val n = board.arrows[result.order.first()].tapNode
            tapper.tapSequence(
                listOf(PointF(board.xs[n.c], board.ys[n.r])), 0,
                onProgress = {},
                onFinished = { ok ->
                    if (!autoRunning) return@tapSequence
                    if (!ok) return@tapSequence stopAuto("Stopped (screen was touched)")
                    autoTaps++
                    // Let the arrow fly away before looking again.
                    main.postAtTime({ autoStep() }, AUTO_TOKEN, SystemClock.uptimeMillis() + Prefs.tapDelay(this))
                },
            )
        }
    }

    /** Hide our UI, grab a screenshot, read the board and solve. */
    private fun analyze(onResult: (Board, SolveResult) -> Unit) {
        if (busy) return
        if (editor != null) toggleEditor() // save the box if it's still being edited
        val grid = Prefs.gridRect(this) ?: run { toast("Set the puzzle area first: tap Grid"); return }
        val cap = capturer ?: return
        val threshold = Prefs.threshold(this)

        busy = true
        clearHints()
        bar?.visibility = View.INVISIBLE
        val hiddenAt = SystemClock.uptimeMillis()
        main.postDelayed({
            worker.execute {
                val out = runCatching {
                    val shot = cap.capture(hiddenAt) ?: return@runCatching null
                    val l = grid.left.toInt().coerceIn(0, shot.width - 1)
                    val t = grid.top.toInt().coerceIn(0, shot.height - 1)
                    val w = (grid.right.toInt().coerceIn(l + 1, shot.width)) - l
                    val h = (grid.bottom.toInt().coerceIn(t + 1, shot.height)) - t
                    val px = IntArray(w * h)
                    shot.getPixels(px, 0, w, l, t, w, h)
                    shot.recycle()
                    val board = ArrowDetector.detect(px, w, h, l.toFloat(), t.toFloat(), threshold)
                        ?: return@runCatching Unit
                    board to Solver.solve(board)
                }.getOrNull()
                main.post {
                    bar?.visibility = View.VISIBLE
                    busy = false
                    @Suppress("UNCHECKED_CAST")
                    when (out) {
                        null -> toast("Couldn't read the screen — try again")
                        Unit -> toast("Couldn't find the dot grid. Make the box cover the whole puzzle, then try again.")
                        else -> (out as Pair<Board, SolveResult>).let { onResult(it.first, it.second) }
                    }
                }
            }
        }, 150)
    }

    private fun showHints(board: Board, result: SolveResult) {
        clearHints()
        val v = HintView(this, board, result)
        wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, touchable = false))
        hintView = v
        raiseBar()
    }

    private fun clearHints() {
        hintView?.let { runCatching { wm.removeView(it) } }
        hintView = null
    }

    private fun report(board: Board, result: SolveResult) {
        val extra = if (board.unreadable > 0) " (${board.unreadable} couldn't be read)" else ""
        val msg = when {
            board.arrows.isEmpty() -> "No arrows found. Make the box cover the puzzle, or lower the sensitivity number."
            result.stuck.isEmpty() -> "${board.arrows.size} arrows — tap them in number order$extra"
            else -> "${result.order.size} moves found, ${result.stuck.size} arrows stuck (red !)$extra. " +
                "Check that the box covers the whole puzzle."
        }
        toast(msg)
    }

    // ---------- misc ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Arrow Helper is running")
            .setContentText("Use the floating bar over your game")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
}
