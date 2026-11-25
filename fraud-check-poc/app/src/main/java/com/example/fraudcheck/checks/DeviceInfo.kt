package com.example.fraudcheck.checks

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import com.example.fraudcheck.core.Signal
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Device facts for the report and for the backend's device graph / model features.
 * Everything here is readable without any dangerous permission and is allowed for loan apps.
 */
object DeviceInfo {

    private fun fmt(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

    fun installer(ctx: Context): String = try {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName ?: "none (sideloaded)"
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName) ?: "none (sideloaded)"
    } catch (e: Exception) { "unknown" }

    fun collect(ctx: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun put(k: String, v: Any?) { out += k to (v?.toString() ?: "n/a") }

        put("Manufacturer / model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        put("Android version", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        put("Security patch", Build.VERSION.SECURITY_PATCH)
        put("Build type / tags", "${Build.TYPE} / ${Build.TAGS}")
        put("Build fingerprint", Build.FINGERPRINT)
        put("CPU ABIs", Build.SUPPORTED_ABIS.joinToString())

        val pi = if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        put("App installed from", installer(ctx) + "  (com.android.vending = Play Store)")
        put("App first installed", fmt(pi.firstInstallTime))
        put("App last updated", fmt(pi.lastUpdateTime))

        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val idHash = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        put("Device ID (hashed ANDROID_ID)", idHash)

        val upMs = SystemClock.elapsedRealtime()
        put("Phone booted", "${fmt(System.currentTimeMillis() - upMs)} (${upMs / 3_600_000} h ago)")

        val dm = ctx.resources.displayMetrics
        put("Screen", "${dm.widthPixels} x ${dm.heightPixels} px, ${dm.densityDpi} dpi")
        put("Language / time zone", "${Locale.getDefault()} / ${TimeZone.getDefault().id}")
        put("Automatic time", if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 1) == 1) "on" else "OFF (manually set clock)")

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) {
            put("SIM operator / country", "${tm.simOperatorName.ifEmpty { "-" }} / ${tm.simCountryIso.ifEmpty { "-" }}")
            put("Network operator / country", "${tm.networkOperatorName.ifEmpty { "-" }} / ${tm.networkCountryIso.ifEmpty { "-" }}")
            val simState = when (tm.simState) {
                TelephonyManager.SIM_STATE_READY -> "ready"; TelephonyManager.SIM_STATE_ABSENT -> "NO SIM"
                else -> "state ${tm.simState}"
            }
            put("SIM state", simState)
            if (Build.VERSION.SDK_INT >= 30) put("SIM slots active", tm.activeModemCount)
        }

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        put("RAM", gb(mem.totalMem))
        val fs = StatFs(ctx.filesDir.absolutePath)
        put("Storage free / total", "${gb(fs.availableBytes)} / ${gb(fs.totalBytes)}")

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        put("Sensors", "${sm.getSensorList(Sensor.TYPE_ALL).size} total")
        put("Developer options", if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) "on" else "off")
        return out
    }

    /** No PIN/pattern/password: common on farm phones, rare on a person's main phone. */
    fun screenLock(ctx: Context): Signal {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val secure = km.isDeviceSecure
        return Signal("device.nolock", "No screen lock set", !secure, weight = 10, evidence = "isDeviceSecure=$secure")
    }

    /**
     * Accessibility services can read and tap the screen. Remote-control fraud and form-filling bots use them.
     * Reading which ones are enabled needs no permission.
     */
    fun accessibility(ctx: Context): Signal {
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val services = raw.split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') }
        // Screen readers and OEM helpers are legitimate; everything else is reported.
        val benign = setOf("com.google.android.marvin.talkback", "com.samsung.android.accessibility.talkback", "com.google.android.apps.accessibility.voiceaccess")
        val unknown = services.filter { it !in benign }
        return Signal("device.accessibility", "Accessibility automation active", unknown.isNotEmpty(), weight = 15,
            evidence = if (services.isEmpty()) "no accessibility services enabled" else "enabled=$services")
    }
}
