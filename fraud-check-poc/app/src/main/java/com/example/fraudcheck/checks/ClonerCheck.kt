package com.example.fraudcheck.checks

import android.content.Context
import android.os.Process
import com.example.fraudcheck.core.Signal

/**
 * App cloner / virtual space detection.
 *
 * Correction to the pasted blueprint: "/data/user/999" is not the main tell.
 * Virtual-space cloners (Parallel Space, VirtualApp forks) run this app inside the HOST app's
 * process and folder, e.g. /data/user/0/com.lbe.parallel.intl/parallel_intl/0/com.example.fraudcheck.
 * So the strongest check is: is our data dir exactly /data/{user,data}/<n>/<our package>, and does
 * the process UID own only our package?
 */
object ClonerCheck {
    private val CLONER_PACKAGES = listOf(
        "com.lbe.parallel.intl", "com.parallel.space.lite", "com.ludashi.dualspace",
        "com.applisto.appcloner", "io.va.exposed",
    )
    private val STANDARD_DIR = Regex("^/data/(data|user/\\d+|user_de/\\d+)/([^/]+)$")

    fun hostFolder(ctx: Context): Signal {
        val dataDir = ctx.applicationInfo.dataDir.orEmpty()
        val files = ctx.filesDir.absolutePath
        val match = STANDARD_DIR.find(dataDir)
        val dirOk = match != null && match.groupValues[2] == ctx.packageName
        val filesOk = files.startsWith(dataDir) && files.removePrefix(dataDir) == "/files"
        val uidPackages = ctx.packageManager.getPackagesForUid(Process.myUid())?.toList().orEmpty()
        val uidOk = uidPackages.isEmpty() || uidPackages == listOf(ctx.packageName)
        val appInfoUidOk = ctx.applicationInfo.uid == Process.myUid()
        val flagged = !dirOk || !filesOk || !uidOk || !appInfoUidOk
        return Signal(
            "cloner.host", "Running inside an app cloner", flagged, weight = 60, hardStop = flagged,
            evidence = "dataDir=$dataDir filesDir=$files uidPackages=$uidPackages appInfoUid=${ctx.applicationInfo.uid} myUid=${Process.myUid()}",
        )
    }

    fun userSpace(): Signal {
        val userId = Process.myUid() / 100_000
        // 0 = main user. 10+ = work profile / secondary user (legitimate). 95, 150, 999 = OEM dual-app / secure-folder spaces.
        val dualSpace = userId in setOf(95, 150, 999)
        return Signal(
            "cloner.userspace", "OEM dual-app space", dualSpace, weight = 15,
            evidence = "androidUserId=$userId (0 main, 10+ work profile, 95/150/999 OEM dual-app spaces)",
        )
    }

    fun clonerAppsInstalled(ctx: Context): Signal {
        val found = ctx.installedOf(CLONER_PACKAGES)
        return Signal("cloner.apps", "Cloner apps installed", found.isNotEmpty(), weight = 10,
            evidence = if (found.isEmpty()) "none of ${CLONER_PACKAGES.size} known hosts" else "found=$found")
    }
}
