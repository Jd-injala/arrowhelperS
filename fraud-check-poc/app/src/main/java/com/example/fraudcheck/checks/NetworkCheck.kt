package com.example.fraudcheck.checks

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.fraudcheck.core.Signal

/** VPN / proxy. Low weight: many genuine users run VPNs. Real value comes from server-side IP reputation. */
object NetworkCheck {
    fun vpnOrProxy(ctx: Context): Signal {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val proxy = System.getProperty("http.proxyHost").orEmpty()
        return Signal("network.vpn", "VPN or proxy active", vpn || proxy.isNotEmpty(), weight = 10,
            evidence = "vpn=$vpn proxyHost='${proxy}' cellular=$cellular")
    }
}
