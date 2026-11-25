package com.example.fraudcheck.checks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import com.example.fraudcheck.core.Signal
import java.io.File

/** Root, emulator and debugging/hooking traces. All are client hints: a determined attacker can hide them. */
object DeviceChecks {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
        "/system/app/Superuser.apk", "/system/bin/.ext/su", "/vendor/bin/su",
    )
    private val ROOT_PACKAGES = listOf("com.topjohnwu.magisk", "eu.chainfire.supersu", "me.weishu.kernelsu")
    private val HOOK_PACKAGES = listOf("org.lsposed.manager", "de.robv.android.xposed.installer")

    fun root(ctx: Context): Signal {
        val su = SU_PATHS.filter { File(it).exists() }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val pkgs = ctx.installedOf(ROOT_PACKAGES)
        val flagged = su.isNotEmpty() || testKeys || pkgs.isNotEmpty()
        return Signal(
            "device.root", "Root traces", flagged, weight = 40,
            evidence = "su=$su testKeys=$testKeys rootApps=$pkgs (Magisk can hide all of these; trust Play Integrity instead)",
        )
    }

    fun emulator(ctx: Context): Signal {
        val hits = mutableListOf<String>()
        val fp = Build.FINGERPRINT.orEmpty()
        if (fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone")) hits += "fingerprint=$fp"
        if (Build.HARDWARE in setOf("goldfish", "ranchu", "vbox86", "nox", "ttVM_x86")) hits += "hardware=${Build.HARDWARE}"
        if (Build.MODEL.contains("Android SDK built for") || Build.MODEL.contains("Emulator")) hits += "model=${Build.MODEL}"
        if (Build.MANUFACTURER.contains("Genymotion", ignoreCase = true)) hits += "manufacturer=${Build.MANUFACTURER}"
        if (Build.PRODUCT.contains("sdk") || Build.PRODUCT.contains("vbox") || Build.PRODUCT.contains("nox")) hits += "product=${Build.PRODUCT}"
        listOf("/dev/qemu_pipe", "/dev/socket/qemud", "/system/lib/libc_malloc_debug_qemu.so", "/system/bin/nox-prop")
            .filter { File(it).exists() }.forEach { hits += "file=$it" }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val missing = listOf(Sensor.TYPE_ACCELEROMETER to "accelerometer", Sensor.TYPE_GYROSCOPE to "gyroscope", Sensor.TYPE_LIGHT to "light")
            .filter { sm.getDefaultSensor(it.first) == null }.map { it.second }
        if (missing.size >= 2) hits += "missingSensors=$missing"
        return Signal("device.emulator", "Emulator traces", hits.isNotEmpty(), weight = 50,
            evidence = if (hits.isEmpty()) "none" else hits.joinToString("; "))
    }

    fun debugAndHooks(ctx: Context): Signal {
        val hits = mutableListOf<String>()
        if (Debug.isDebuggerConnected()) hits += "debugger attached"
        val debuggable = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val adb = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val maps = try {
            File("/proc/self/maps").useLines { lines ->
                lines.map { it.lowercase() }
                    .filter { l -> listOf("frida", "xposed", "lsposed", "substrate", "riru", "zygisk").any { l.contains(it) } }
                    .map { it.substringAfterLast(' ') }.distinct().take(5).toList()
            }
        } catch (_: Exception) { emptyList() }
        if (maps.isNotEmpty()) hits += "hooked libs in memory=$maps"
        val xposedOnStack = Throwable().stackTrace.any { it.className.startsWith("de.robv.android.xposed") }
        if (xposedOnStack) hits += "xposed on call stack"
        val hookApps = ctx.installedOf(HOOK_PACKAGES)
        if (hookApps.isNotEmpty()) hits += "hook managers=$hookApps"
        // ADB on its own is common for developers, so it is reported but does not flag.
        return Signal("device.hooks", "Debugger or hooking framework", hits.isNotEmpty(), weight = 40,
            evidence = (if (hits.isEmpty()) "none" else hits.joinToString("; ")) + " | adbEnabled=$adb debuggableBuild=$debuggable")
    }
}
