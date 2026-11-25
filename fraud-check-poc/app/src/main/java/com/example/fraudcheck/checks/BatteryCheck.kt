package com.example.fraudcheck.checks

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.fraudcheck.core.Signal

/** Phone farms are usually plugged in at 100%; emulators often report fixed values. Weak on its own. */
object BatteryCheck {
    fun farmLike(ctx: Context): Signal {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tempC = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        val flagged = (pct == 100 && plugged) || tempC == 0.0
        return Signal("battery.farm", "Battery looks like a farm or emulator", flagged, weight = 10,
            evidence = "level=$pct% plugged=$plugged temp=${tempC}C")
    }
}
