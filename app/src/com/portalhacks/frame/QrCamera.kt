package com.portalhacks.frame

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * The scanner's camera on the Camera2 API: one luma stream, no preview surface.
 *
 * What was measured on a Portal+ (codename `cipher`, 2026-11), and why this class is as plain
 * as it is:
 *
 *  - The app is offered a single camera, id 0: 1280x720 over a 96 degree view, fixed focus.
 *    The 12 MP sensor behind it is id 1, which the Portal's camera service opens for
 *    preinstalled apps only ("app is NOT preload"); asking for it by id fails at open time.
 *  - That 720p feed ignores every control: digital zoom (legacy `zoom` and Camera2 crop
 *    region both return the identical frame), exposure compensation and metering regions.
 *    So there is nothing to steer, and steering attempts only disturb the decoder.
 *
 * What is left to win is time and attention: frames are copied off the camera thread and
 * decoded on a worker (the newest frame waits, older ones are dropped), which keeps the
 * camera at its full rate and gets five times as many frames in front of the decoder. The
 * viewfinder is drawn from the same frames, so a located code can be boxed exactly.
 */
internal class QrCamera(context: Context, private val listener: Listener) {
    interface Listener {
        /** A frame to decode, on the decode thread. The buffer is reused after this returns. */
        fun onFrame(frame: Luma)

        /** A small, mirrored picture of a frame for the viewfinder, on the camera thread. */
        fun onViewfinder(frame: Luma)

        /** The camera could not be used; the caller should fall back. On the camera thread. */
        fun onFailed(why: String)
    }

    private val ctx = context.applicationContext
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    private var running = false

    var width = 0
        private set
    var height = 0
        private set

    // The newest undecoded frame, and the buffers frames borrow: one waiting, one being
    // filled, one being decoded.
    private val waiting = AtomicReference<ByteArray?>(null)
    private val pool = ArrayBlockingQueue<ByteArray>(BUFFERS)
    private val wake = Object()
    private var frameNo = 0

    /** Open the most detailed camera on offer and start delivering frames. False if none works. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val pick =
            try {
                pickCamera()
            } catch (e: Exception) {
                Log.w(TAG, "QR cam2: cannot list cameras", e)
                null
            } ?: return false
        width = pick.second.width
        height = pick.second.height
        Log.i(TAG, "QR cam2: camera ${pick.first} ${width}x$height of ${manager.cameraIdList.size} offered")

        try {
            repeat(BUFFERS) { pool.add(ByteArray(width * height)) }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "QR cam2: not enough memory for ${width}x$height frames")
            return false
        }

        running = true
        val t = HandlerThread("qr-camera").also { it.start() }
        camThread = t
        val h = Handler(t.looper)
        camHandler = h
        Thread({ decodeLoop() }, "qr-decode").apply { isDaemon = true }.start()

        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener({ onImage(it) }, h)
        try {
            manager.openCamera(pick.first, deviceCallback, h)
        } catch (e: Exception) {
            Log.w(TAG, "QR cam2: open failed", e)
            stop()
            return false
        }
        return true
    }

    fun stop() {
        running = false
        synchronized(wake) { wake.notifyAll() }
        val h = camHandler
        val t = camThread
        camHandler = null
        camThread = null
        if (h != null && t != null) {
            h.post {
                try {
                    session?.close()
                } catch (_: Exception) {
                }
                try {
                    device?.close()
                } catch (_: Exception) {
                }
                try {
                    reader?.close()
                } catch (_: Exception) {
                }
                session = null
                device = null
                reader = null
                t.quitSafely()
            }
        }
    }

    /** The listed camera with the widest luma stream that still runs at a useful rate. */
    private fun pickCamera(): Pair<String, Size>? {
        var best: Pair<String, Size>? = null
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: continue
            for (s in sizes) {
                if (s.width < s.height || s.width > MAX_WIDTH) continue
                if (map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, s) > MAX_FRAME_NS) continue
                val b = best
                if (b == null || s.width.toLong() * s.height > b.second.width.toLong() * b.second.height) {
                    best = Pair(id, s)
                }
            }
        }
        return best
    }

    private val deviceCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                val r = reader ?: return
                try {
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(r.surface), sessionCallback, camHandler)
                } catch (e: Exception) {
                    fail("session: $e")
                }
            }

            override fun onDisconnected(d: CameraDevice) {
                fail("camera taken by another app")
            }

            override fun onError(
                d: CameraDevice,
                error: Int,
            ) {
                fail("camera error $error")
            }
        }

    private val sessionCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val d = device ?: return
                val r = reader ?: return
                try {
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(r.surface)
                    b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    // A fixed high frame rate caps the exposure time, which limits motion blur.
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    s.setRepeatingRequest(b.build(), null, camHandler)
                } catch (e: CameraAccessException) {
                    fail("request: $e")
                } catch (e: IllegalStateException) {
                    // closed under us while stopping
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                fail("stream ${width}x$height refused")
            }
        }

    private fun fail(why: String) {
        if (!running) return
        Log.w(TAG, "QR cam2: $why")
        listener.onFailed(why)
    }

    private fun onImage(r: ImageReader) {
        val img =
            try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return
        try {
            if (!running) return
            val buf = pool.poll() ?: return
            val plane = img.planes[0]
            val src = plane.buffer
            val stride = plane.rowStride
            if (stride == width) {
                src.get(buf, 0, Math.min(src.remaining(), width * height))
            } else {
                for (y in 0 until height) {
                    src.position(y * stride)
                    src.get(buf, y * width, width)
                }
            }
            // The viewfinder needs no more than about 15 pictures a second.
            if (frameNo++ % 2 == 0) listener.onViewfinder(viewfinder(buf))
            val old = waiting.getAndSet(buf)
            if (old != null) pool.offer(old)
            synchronized(wake) { wake.notifyAll() }
        } catch (e: Exception) {
            Log.w(TAG, "QR cam2: frame copy failed", e)
        } finally {
            img.close()
        }
    }

    /** A small, mirrored copy of the frame for the screen (point-sampled; it only has to aim). */
    private fun viewfinder(buf: ByteArray): Luma {
        val f = Math.max(1, width / VIEW_W)
        val w = width / f
        val h = height / f
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * f * width
            val o = y * w
            for (x in 0 until w) out[o + (w - 1 - x)] = buf[row + x * f]
        }
        return Luma(out, w, h)
    }

    private fun decodeLoop() {
        while (running) {
            val buf = waiting.getAndSet(null)
            if (buf == null) {
                synchronized(wake) {
                    try {
                        wake.wait(200)
                    } catch (_: InterruptedException) {
                    }
                }
                continue
            }
            try {
                listener.onFrame(Luma(buf, width, height))
            } catch (e: Exception) {
                Log.w(TAG, "QR cam2: decode failed", e)
            } finally {
                pool.offer(buf)
            }
        }
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val BUFFERS = 3

        /** Slowest acceptable stream: 12 frames a second. */
        private const val MAX_FRAME_NS = 84_000_000L
        private const val MAX_WIDTH = 4100
        private const val VIEW_W = 640
    }
}
