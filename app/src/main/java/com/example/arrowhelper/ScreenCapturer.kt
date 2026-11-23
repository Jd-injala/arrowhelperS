package com.example.arrowhelper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Mirrors the screen into an ImageReader and keeps the latest frame.
 * Android 14+ allows one virtual display per consent, so we keep it open for the
 * life of the overlay instead of re-creating it for every screenshot.
 */
class ScreenCapturer(
    context: Context,
    resultCode: Int,
    data: Intent,
    private val onStopped: () -> Unit,
) {
    val width: Int
    val height: Int

    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val projection: MediaProjection
    private val reader: ImageReader
    private val display: VirtualDisplay

    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var frame: Bitmap? = null
    private var scratch: ByteBuffer? = null
    private var frameTime = 0L
    @Volatile private var released = false

    init {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            width = b.width(); height = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            width = m.widthPixels; height = m.heightPixels
        }
        val density = context.resources.displayMetrics.densityDpi
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Screen capture is not available")
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!released) { release(); onStopped() }
            }
        }, handler)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onImage(it) }, handler)
        display = projection.createVirtualDisplay(
            "arrow-helper", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val plane = img.planes[0]
            val stridePx = plane.rowStride / plane.pixelStride
            val src = plane.buffer
            lock.withLock {
                var bmp = frame
                if (bmp == null || bmp.width != stridePx || bmp.height != img.height) {
                    bmp = Bitmap.createBitmap(stridePx, img.height, Bitmap.Config.ARGB_8888)
                    frame = bmp
                }
                val needed = plane.rowStride * img.height
                src.rewind()
                if (src.remaining() >= needed) {
                    bmp.copyPixelsFromBuffer(src)
                } else {
                    // Last row is often shorter than rowStride; pad it out.
                    var tmp = scratch
                    if (tmp == null || tmp.capacity() != needed) {
                        tmp = ByteBuffer.allocateDirect(needed); scratch = tmp
                    }
                    tmp!!.clear(); tmp.put(src); tmp.rewind()
                    bmp.copyPixelsFromBuffer(tmp)
                }
                frameTime = SystemClock.uptimeMillis()
                newFrame.signalAll()
            }
        } finally {
            img.close()
        }
    }

    /** Returns a copy of the first frame produced after [newerThan] (uptime ms), or the latest one on timeout. */
    fun capture(newerThan: Long, timeoutMs: Long = 700): Bitmap? = lock.withLock {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (frameTime <= newerThan) {
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0) break
            newFrame.await(left, TimeUnit.MILLISECONDS)
        }
        val f = frame ?: return null
        Bitmap.createBitmap(f, 0, 0, minOf(width, f.width), minOf(height, f.height))
            .let { if (it === f) f.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { display.release() }
        runCatching { reader.close() }
        runCatching { projection.stop() }
        thread.quitSafely()
    }
}
