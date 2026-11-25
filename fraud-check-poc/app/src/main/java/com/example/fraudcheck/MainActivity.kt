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
import androidx.lifecycle.lifecycleScope
import com.example.fraudcheck.checks.BatteryCheck
import com.example.fraudcheck.checks.BehaviourTracker
import com.example.fraudcheck.checks.CameraCheck
import com.example.fraudcheck.checks.ClonerCheck
import com.example.fraudcheck.checks.DeviceChecks
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
                type = "application/json"; putExtra(Intent.EXTRA_TEXT, json)
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
            withContext(Dispatchers.Default) {
                val ctx = this@MainActivity
                fun add(id: String, block: () -> Signal) {
                    signals += try { block() } catch (e: Exception) { Signal.error(id, id, e) }
                }
                add("device.root") { DeviceChecks.root(ctx) }
                add("device.emulator") { DeviceChecks.emulator(ctx) }
                add("device.hooks") { DeviceChecks.debugAndHooks(ctx) }
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
            lastJson = RiskScorer.toJson(result, sessionId)
            report.text = render(result)
            share.isEnabled = true
        }
    }

    private fun render(r: RiskReport): String = buildString {
        appendLine("RISK SCORE  ${r.score}/100   ->  ${r.band.label}")
        if (r.hardStops.isNotEmpty()) appendLine("HARD STOP: ${r.hardStops.joinToString { it.title }}")
        appendLine("(Local preview only. The backend must verify the Play Integrity token and key attestation.)")
        appendLine()
        for (s in r.signals) {
            val mark = if (s.flagged) "[FLAG +${s.weight}]" else "[ ok ]"
            appendLine("$mark ${s.title}  (${s.trust})")
            appendLine("    ${s.evidence}")
        }
    }
}
