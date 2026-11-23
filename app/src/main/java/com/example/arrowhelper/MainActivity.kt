package com.example.arrowhelper

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var thresholdInput: EditText
    private lateinit var delayInput: EditText

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                moveTaskToBack(true) // go back to the game
            } else {
                toast("Screen capture was not allowed, so the helper can't see the puzzle")
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Arrow Helper"; textSize = 24f })
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)

        thresholdInput = numberField(root, "Detection sensitivity (lower = picks up fainter arrows, default 90)", Prefs.threshold(this))
        delayInput = numberField(root, "Pause after each auto-tap in ms (default 700)", Prefs.tapDelay(this).toInt())

        button(root, "Save settings") { saveSettings(); toast("Saved") }
        button(root, "1. Allow drawing over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(root, "2. Turn on auto-tap (Accessibility → Arrow Helper)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            button(root, "3. Allow notification (required to keep the overlay running)") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        button(root, "▶ Start overlay") { startOverlay() }
        button(root, "■ Stop overlay") { stopService(Intent(this, OverlayService::class.java)) }

        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = "How to use:\n" +
                "• Open the game, tap Grid on the floating bar and drag the box so it covers the whole puzzle (all the dots, with a little margin). Tap Save.\n" +
                "• Hint: a number on each arrowhead shows the order to tap. Red ! = an arrow that can't leave (usually the box doesn't cover the whole puzzle).\n" +
                "• Auto: taps one free arrow, looks at the board again, and repeats until it is clear. Tap Stop to cancel.\n" +
                "• Drag the bar by its ⠿ handle so it isn't covering the puzzle."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val tap = TapService.instance != null
        status.text = "Draw over apps: ${if (overlay) "✅" else "❌"}\n" +
            "Auto-tap (accessibility): ${if (tap) "✅" else "❌ (Hint still works without it)"}"
    }

    private fun startOverlay() {
        saveSettings()
        if (!Settings.canDrawOverlays(this)) {
            toast("First allow drawing over other apps (step 1)")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun saveSettings() {
        fun EditText.int(def: Int, lo: Int, hi: Int) = (text.toString().toIntOrNull() ?: def).coerceIn(lo, hi)
        Prefs.save(
            this,
            6,
            6,
            thresholdInput.int(90, 10, 400),
            delayInput.int(700, 100, 5000),
        )
    }

    private fun numberField(root: LinearLayout, label: String, value: Int): EditText {
        root.addView(TextView(this).apply { text = label })
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            root.addView(this)
        }
    }

    private fun button(root: LinearLayout, label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
