package com.example.fraudcheck.checks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.fraudcheck.core.Signal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Mock-location check using COARSE location only (ACCESS_FINE_LOCATION is banned for loan apps on Play).
 * The coarse fix, IP geolocation and SIM country are compared on the server, not here.
 */
object LocationCheck {
    private val FAKE_GPS_APPS = listOf("com.lexa.fakegps", "com.incorporateapps.fakegps.fre")

    suspend fun mock(ctx: Context): Signal {
        val fakeApps = ctx.installedOf(FAKE_GPS_APPS)
        val simCountry = (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso.orEmpty()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return Signal("location.mock", "Mock location", fakeApps.isNotEmpty(), weight = 20,
                evidence = "location permission not granted; fakeGpsApps=$fakeApps simCountry=$simCountry")
        }
        val loc = currentCoarse(ctx)
        val isMock = loc?.let {
            if (Build.VERSION.SDK_INT >= 31) it.isMock else @Suppress("DEPRECATION") it.isFromMockProvider
        } ?: false
        val where = loc?.let { "lat=%.2f lon=%.2f acc=%.0fm provider=%s".format(it.latitude, it.longitude, it.accuracy, it.provider) } ?: "no fix"
        return Signal("location.mock", "Mock location", isMock || fakeApps.isNotEmpty(), weight = if (isMock) 40 else 20,
            evidence = "isMock=$isMock $where fakeGpsApps=$fakeApps simCountry=$simCountry (send coarse fix to server for IP/SIM comparison)")
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentCoarse(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = LocationManager.NETWORK_PROVIDER
        if (Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(provider)) {
            val fresh = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { cont.resume(it) }
                }
            }
            if (fresh != null) return fresh
        }
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}
