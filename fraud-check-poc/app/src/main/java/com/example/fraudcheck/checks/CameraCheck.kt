package com.example.fraudcheck.checks

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.example.fraudcheck.core.Signal

/**
 * Camera sanity before the selfie step. WEAK signal: most virtual-camera injection tools hook the
 * camera inside the app, so characteristics look normal. Real defence is the liveness vendor's
 * injection detection plus server-side checks on the captured frames.
 */
object CameraCheck {
    fun sanity(ctx: Context): Signal {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.cameraIdList.toList()
        var front = 0
        var external = 0
        val details = ids.map { id ->
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front".also { front++ }
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external".also { external++ }
                else -> "unknown"
            }
            val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
                else -> "?"
            }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") ?: "n/a"
            "$id:$facing/$level/f=$focal"
        }
        val flagged = ids.isEmpty() || front == 0 || external > 0
        return Signal("camera.sanity", "Camera setup unusual", flagged, weight = 15,
            evidence = "cameras=${ids.size} front=$front external=$external $details")
    }
}
