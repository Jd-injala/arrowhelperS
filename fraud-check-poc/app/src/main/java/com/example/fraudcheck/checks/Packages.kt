package com.example.fraudcheck.checks

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Only finds packages declared in the manifest <queries> block (QUERY_ALL_PACKAGES is banned for loan apps). */
internal fun Context.installedOf(packages: List<String>): List<String> = packages.filter { pkg ->
    try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") packageManager.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
