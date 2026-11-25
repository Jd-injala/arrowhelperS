package com.example.fraudcheck

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.fraudcheck.checks.BatteryCheck
import com.example.fraudcheck.checks.BehaviourTracker
import com.example.fraudcheck.checks.CameraCheck
import com.example.fraudcheck.checks.ClonerCheck
import com.example.fraudcheck.checks.DeviceChecks
import com.example.fraudcheck.checks.DeviceInfo
import com.example.fraudcheck.checks.IntegrityCheck
import com.example.fraudcheck.checks.LocationCheck
import com.example.fraudcheck.checks.NetworkCheck
import com.example.fraudcheck.checks.SensorRecorder
import com.example.fraudcheck.core.RiskReport
import com.example.fraudcheck.core.RiskScorer
import com.example.fraudcheck.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val behaviour = BehaviourTracker()
    private lateinit var sensors: SensorRecorder
    private lateinit var report: TextView
    private lateinit var share: Button
    private var lastJson: String? = null

    // One-time, foreground, COARSE location only (fine location is banned for loan apps on Play).
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { runChecks() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Android 15 draws apps edge to edge: keep content clear of the status and navigation bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(view<View>(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        sensors = SensorRecorder(this)
        report = view(R.id.txtReport)
        share = view(R.id.btnShare)
        behaviour.watch(view<EditText>(R.id.fieldName), "name")
        behaviour.watch(view<EditText>(R.id.fieldPan), "pan")
        behaviour.watch(view<EditText>(R.id.fieldPhone), "phone")
        view<Button>(R.id.btnRun).setOnClickListener {
            askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        share.setOnClickListener {
            val json = lastJson ?: return@setOnClickListener
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, json)
            }, "Share risk report"))
        }
    }

    private fun <T : View> view(id: Int): T = findViewById<T>(id) ?: error("missing view $id")

    override fun onResume() { super.onResume(); sensors.start() }
    override fun onPause() { sensors.stop(); super.onPause() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        behaviour.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun runChecks() {
        report.text = "Running checks..."
        lifecycleScope.launch {
            // In production the sessionId comes from your backend (one-time nonce).
            val sessionId = UUID.randomUUID().toString()
            val signals = mutableListOf<Signal>()
            var device: List<Pair<String, String>> = emptyList()
            withContext(Dispatchers.Default) {
                val ctx = this@MainActivity
                fun add(id: String, block: () -> Signal) {
                    signals += try { block() } catch (e: Exception) { Signal.error(id, id, e) }
                }
                device = try { DeviceInfo.collect(ctx) } catch (e: Exception) { listOf("error" to e.toString()) }
                add("device.root") { DeviceChecks.root(ctx) }
                add("device.emulator") { DeviceChecks.emulator(ctx) }
                add("device.hooks") { DeviceChecks.debugAndHooks(ctx) }
                add("device.accessibility") { DeviceInfo.accessibility(ctx) }
                add("device.nolock") { DeviceInfo.screenLock(ctx) }
                add("cloner.host") { ClonerCheck.hostFolder(ctx) }
                add("cloner.userspace") { ClonerCheck.userSpace() }
                add("cloner.apps") { ClonerCheck.clonerAppsInstalled(ctx) }
                add("network.vpn") { NetworkCheck.vpnOrProxy(ctx) }
                add("battery.farm") { BatteryCheck.farmLike(ctx) }
                add("camera.sanity") { CameraCheck.sanity(ctx) }
                add("sensors.variance") { sensors.signal() }
                add("behaviour.paste") { behaviour.pasteSignal() }
                add("behaviour.typing") { behaviour.typingSignal() }
                add("behaviour.touch") { behaviour.touchSignal() }
                add("integrity.keystore") { IntegrityCheck.keyAttestation(ctx, sessionId.toByteArray()).signal }
            }
            signals += try { LocationCheck.mock(this@MainActivity) } catch (e: Exception) { Signal.error("location.mock", "Mock location", e) }

            // Bind the Play Integrity token to this exact signal set.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(signals.joinToString("|") { "${it.id}:${it.flagged}" }.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val integrity = IntegrityCheck.playIntegrity(this@MainActivity, IntegrityCheck.requestHash(sessionId, digest))
            signals += integrity.signal

            val result = RiskScorer.score(signals)
            lastJson = RiskScorer.toJson(result, sessionId, device)
            report.text = render(result, device)
            share.isEnabled = true
        }
    }

    /** Plain-English description of what each check looked for. */
    private val meaning = mapOf(
        "device.root" to "Looked for su binaries, test-keys builds and root manager apps.",
        "device.emulator" to "Looked for emulator build names, emulator files and missing sensors.",
        "device.hooks" to "Looked for a debugger and Frida/Xposed/LSPosed code loaded into this app.",
        "device.accessibility" to "Listed accessibility services that can read and tap the screen (remote-control and bot tools use them).",
        "device.nolock" to "Checked that the phone has a PIN, pattern or password. Farm phones usually have none.",
        "cloner.host" to "Checked this app's data folder and user ID belong to this app alone, not a cloner like Parallel Space.",
        "cloner.userspace" to "Checked whether the app runs in an OEM dual-app space (user 95, 150 or 999).",
        "cloner.apps" to "Looked for 5 known app-cloner apps.",
        "network.vpn" to "Checked for a VPN or HTTP proxy. Weak: many genuine users run VPNs.",
        "battery.farm" to "Checked for battery stuck at 100% while charging, typical of phone farms.",
        "camera.sanity" to "Checked there is a front camera and no external (USB/virtual) camera.",
        "sensors.variance" to "Measured hand tremor in the motion sensors while you filled the form. Emulators show none.",
        "behaviour.paste" to "Counted pastes or autofills into the name, PAN and phone fields.",
        "behaviour.typing" to "Measured the gaps between key presses. Scripts type at a fixed, fast rhythm.",
        "behaviour.touch" to "Compared touch size and pressure across taps. Injected taps are identical.",
        "integrity.keystore" to "Made a key inside the phone's secure chip and got Google's certificate for it. Emulators can't.",
        "location.mock" to "Took one approximate location and checked it was not faked by a mock-GPS app.",
        "integrity.play" to "Asks Google Play to vouch for the phone and this app. Needs setup on your side.",
    )

    private fun render(r: RiskReport, device: List<Pair<String, String>>): String = buildString {
        val flagged = r.signals.count { it.flagged }
        appendLine("RISK SCORE  ${r.score}/100   ->  ${r.band.label}")
        appendLine(
            if (flagged == 0) "All ${r.signals.size} checks passed. This is what a genuine phone in a person's hand should look like."
            else "$flagged of ${r.signals.size} checks flagged. Flagged items are listed first."
        )
        if (r.hardStops.isNotEmpty()) appendLine("HARD STOP: ${r.hardStops.joinToString { it.title }}")
        appendLine("(Local preview only. The backend must verify the Play Integrity token and key attestation.)")
        appendLine()
        appendLine("=== CHECKS ===")
        for (s in r.signals) {
            val mark = if (s.flagged) "[FLAG +${s.weight}]" else "[ ok ]"
            appendLine("$mark ${s.title}")
            meaning[s.id]?.let { appendLine("    Checked: $it") }
            appendLine("    Found:   ${s.evidence}")
            appendLine("    Trust:   ${if (s.trust.name == "SERVER_VERIFIED") "server can verify" else "phone-side hint only"}")
            appendLine()
        }
        appendLine("=== DEVICE INFORMATION ===")
        for ((k, v) in device) appendLine("$k: $v")
        appendLine()
        appendLine("=== HOW TO SEE CHECKS FAIL ===")
        appendLine("- Paste into the PAN field -> paste flag")
        appendLine("- Turn on a VPN -> VPN flag")
        appendLine("- Run inside Parallel Space or Dual Apps -> cloner hard stop")
        appendLine("- Use a fake GPS app (set as mock location app) -> mock location flag")
        appendLine("- Install on an Android Studio emulator -> emulator, sensor and key attestation flags")
        appendLine("- Remove the screen lock -> no screen lock flag")
    }
}
