package com.iherdt.lumaframe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device setup, shown as the "Photos" app icon. Styled with the Portal design
 * system ([Ui]: dark theme, platform palette, Inter type, room-distance hit
 * targets, centred max-width column, reserved top overlay inset). Three screens:
 *
 *  - Status/settings: album currently playing, how to add one, slideshow settings.
 *  - Scanner: live camera QR scan (validated, added to the album list via [Albums]).
 *  - Phone hand-off: a QR *on the frame* that opens a page on a phone where the album link
 *    can be pasted ([LocalDropServer]); nothing has to be held up to the camera.
 *  - Manual entry: type/paste the link with the on-screen keyboard (QR-less fallback).
 *
 * All persist to the same `lumaframe` prefs the slideshow reads ([ConfigReceiver]).
 *
 * The scanner has two camera paths. [QrCamera] (Camera2) drives the real sensor at full
 * resolution with its own exposure bracket and is used wherever it opens. The legacy
 * [Camera] path below is the fallback (and what a Portal Go's single 720p camera gets).
 * Both hand their frames to the same [QrSearch] on a background thread, so the camera
 * keeps its full frame rate while a frame is being decoded.
 */
@Suppress("DEPRECATION")
class PhotosActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val search = QrSearch()

    // Camera2 path
    private var cam2: QrCamera? = null
    private var viewfinder: ViewfinderView? = null
    private var viewport: FrameLayout? = null

    @Volatile
    private var lastSeenMs = 0L // when a code was last located (found but not yet read)
    private var seenSinceMs = 0L // when the current sighting began
    private var statsAt = 0L
    private var statsFrames = 0
    private var statsMs = 0L
    private var statsPasses = 0
    private var dumpLeft = 0

    // Legacy path: one decode at a time, off the camera callback.
    private var legacyWorker: ExecutorService? = null
    private val legacyBusy = AtomicBoolean(false)
    private var legacyBuf: ByteArray? = null

    // A phone can add the album through the hand-off page while this screen is open.
    private var albumReceiverRegistered = false
    private val albumReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context?,
                i: Intent?,
            ) {
                if (isFinishing) return
                stopCamera()
                scanHint?.text = "Album added from your phone ✓"
                toast("Album added ✓")
                main.postDelayed({ finish() }, 1500)
            }
        }

    private lateinit var root: FrameLayout

    // Scanner state
    private var camera: Camera? = null
    private var cameraId = 0 // remembered so onConfigurationChanged can re-orient the preview
    private var surface: SurfaceView? = null
    private var scanHint: TextView? = null

    @Volatile
    private var scanning = false
    private var previewW = 0
    private var previewH = 0 // cached so we don't hit getParameters() per frame
    private var frameCount = 0

    // Closed-loop exposure. This camera's AE can't be set directly (EV is ignored, AE can't be
    // disabled), but ZOOM moves it: filling the frame with the bright phone screen makes the AE
    // meter the screen and expose DOWN, out of clipping. So we measure each frame's central
    // brightness and drive the zoom toward a target — blown out → zoom in (more fill → darker),
    // too dark → zoom out. This self-tunes to any phone brightness, even when it can't be lowered.
    private var maxZoomIdx = 0
    private var curZoom = 0
    private var zoomSettle = 0 // frames to let the AE settle after a zoom change before re-judging
    private var autoExpZoom = true // false when a fixed `zoompct` override pins the level
    private var zoomWant = 0 // direction the last reading asked for
    private var zoomVotes = 0 // consecutive readings that asked for it

    // Per-device QR tuning — picked once. The Portal models' front cameras and screen orientations
    // differ enough that one device's optimization regresses another (the Portal+ portrait
    // closed-loop zoom + rotated decode passes broke the Portal Go's landscape fixed-focus lens).
    private val tuning = QrTuning.forThisDevice()

    /**
     * Per-device QR-scanner knobs, isolated so an optimization for one Portal can't regress another.
     */
    private class QrTuning(
        /** Use the Camera2 path ([QrCamera]); the legacy camera stays as its fallback. */
        val camera2: Boolean,
        /** Legacy path: pin digital zoom at this % of max and skip the closed-loop controller; 0 = no pin. */
        val fixedZoomPct: Int,
        /** Legacy path: starting zoom (% of max) for the closed-loop controller; 0 = leave the zoom alone. */
        val loopStartZoomPct: Int,
    ) {
        companion object {
            fun forThisDevice(): QrTuning {
                val id = (Build.DEVICE + " " + Build.MODEL + " " + Build.PRODUCT).lowercase(Locale.US)
                return when {
                    // Portal Go (codename "terry"): landscape screen, fixed-focus ultra-wide front
                    // camera. A pinned high zoom both enlarges the QR and forces the AE to expose for
                    // the bright screen. This is the original known-good recipe (commit 6f2e712).
                    id.contains("terry") || id.contains("portalgo") ->
                        QrTuning(camera2 = false, fixedZoomPct = 90, loopStartZoomPct = 90)
                    // Portal+ 14" (codename "cipher"): measured, the feed ignores zoom and every
                    // exposure control (see QrCamera), so no steering at all; Camera2 for its
                    // viewfinder overlay and frame rate.
                    id.contains("cipher") ->
                        QrTuning(camera2 = true, fixedZoomPct = 0, loopStartZoomPct = 0)
                    // Other Portals, not measured: the legacy camera with the adaptive
                    // closed-loop zoom, as before.
                    else ->
                        QrTuning(camera2 = false, fixedZoomPct = 0, loopStartZoomPct = 70)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Pan the screen up when the on-screen keyboard appears so the link field stays visible.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)
        dumpLeft = intent?.getIntExtra("dump", 0) ?: 0
        // The hand-off QR on this screen points at the LAN server; make sure it is up.
        DropServerService.start(this)
        // The Compose SettingsActivity hands off here purely for the camera scanner /
        // manual-entry panel (it owns all other settings).
        startScan()
    }

    override fun onResume() {
        super.onResume()
        if (!albumReceiverRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                albumReceiver,
                IntentFilter(DropServerService.ACTION_ALBUMS),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            albumReceiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (albumReceiverRegistered) {
            unregisterReceiver(albumReceiver)
            albumReceiverRegistered = false
        }
        stopCamera()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles orientation changes itself (configChanges in the manifest), so the
        // camera isn't torn down when the Portal screen is rotated — but its preview keeps the
        // orientation it had when it opened, leaving the live preview sideways. Re-apply the
        // display orientation and aspect for the new rotation. (Decode is rotation-agnostic; this
        // is purely the on-screen preview.) Stop/start around setDisplayOrientation since some
        // HALs reject it mid-preview.
        val c = camera ?: return
        try {
            c.stopPreview()
            val orientation = previewDisplayOrientation(cameraId)
            c.setDisplayOrientation(orientation)
            if (previewW != 0 && previewH != 0) {
                sizePreviewToAspect(previewW, previewH, orientation)
            }
            c.startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "re-orient on rotation failed", e)
        }
    }

    // ---------------------------------------------------------------- prefs

    private fun prefs(): SharedPreferences = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)

    private fun topMargin(
        v: View,
        dp: Int,
    ) {
        var lp = v.layoutParams as? LinearLayout.LayoutParams
        if (lp == null) {
            lp = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        lp.topMargin = Ui.dp(this, dp.toFloat())
        v.layoutParams = lp
    }

    /** A centered section heading for the scanner screen. */
    private fun sectionHeading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFF0F0F0.toInt())
        t.typeface = Ui.bold(this)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        return t
    }

    // ------------------------------------------------ Manual entry

    /**
     * "Done" on the add screen: add the pasted link (if any) and close. An empty field just
     * closes (e.g. after a QR scan already added one); an invalid link warns and stays.
     */
    private fun addTypedAlbum(edit: EditText) {
        val url = edit.text.toString().trim()
        if (url.isEmpty()) {
            finish()
            return
        }
        if (!isPhotosLink(url)) {
            toast("That doesn't look like a Google Photos or iCloud link")
            return
        }
        Albums.add(prefs(), url)
        Log.i(TAG, "album added via manual entry: $url")
        hideKeyboard(edit)
        toast("Album added ✓")
        finish() // back to the Compose settings screen
    }

    /** A compact, content-width filled button (not full-bleed like the card buttons). */
    private fun pillButton(
        label: String,
        fill: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.typeface = Ui.medium(this)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        b.setTextColor(textColor)
        val h = Ui.dp(this, 56f)
        b.minHeight = h
        b.minimumHeight = h
        b.stateListAnimator = null
        val padH = Ui.dp(this, 36f)
        b.setPadding(padH, 0, padH, 0)
        b.background = Ui.roundRect(fill, Ui.dp(this, 14f))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ------------------------------------------------------ Scanner

    private fun startScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        showScanner()
    }

    override fun onRequestPermissionsResult(
        req: Int,
        perms: Array<out String>,
        results: IntArray,
    ) {
        if (req == REQ_CAMERA) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                showScanner()
            } else {
                toast("Camera permission is needed to scan the QR code")
                finish()
            }
        }
    }

    private fun showScanner() {
        root.removeAllViews()
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels

        // Two columns side by side on a landscape screen, stacked on a portrait one:
        // the camera scanner, and the ways to add an album without it.
        val page = LinearLayout(this)
        page.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        page.gravity = if (landscape) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
        val pad = Ui.dp(this, 24f)
        page.setPadding(pad, Ui.dp(this, 16f), pad, pad)

        val colW =
            Math.min(
                Ui.dp(this, 640f),
                (if (landscape) dm.widthPixels / 2 else dm.widthPixels) - Ui.dp(this, 72f),
            )

        // ---- column 1: the scanner
        val scanCol = LinearLayout(this)
        scanCol.orientation = LinearLayout.VERTICAL
        scanCol.gravity = Gravity.CENTER_HORIZONTAL

        val title = TextView(this)
        title.text = "Add an album"
        title.setTextColor(0xFFF0F0F0.toInt())
        title.typeface = Ui.bold(this)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.gravity = Gravity.CENTER_HORIZONTAL
        scanCol.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))

        val scanHeading = sectionHeading("Scan a QR code")
        scanCol.addView(scanHeading, below(20f))

        // The viewfinder shows the camera's whole view (4:3), so the code can be anywhere in it.
        val vp = FrameLayout(this)
        vp.background = Ui.roundRect(Color.BLACK, Ui.dp(this, 20f))
        vp.clipToOutline = true
        viewport = vp
        val vpW = colW
        val vpH = vpW * 3 / 4
        scanCol.addView(vp, LinearLayout.LayoutParams(vpW, vpH).apply { topMargin = Ui.dp(this@PhotosActivity, 12f) })

        val subtitle = TextView(this)
        this.scanHint = subtitle // reused for "that QR isn't…/Album added ✓" feedback
        subtitle.text = DEFAULT_SCAN_HINT
        subtitle.setTextColor(0xFFD2D2D2.toInt())
        subtitle.typeface = Ui.medium(this)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.setLineSpacing(Ui.dp(this, 3f).toFloat(), 1f)
        subtitle.minLines = 2
        scanCol.addView(subtitle, below(12f))

        // ---- column 2: without the camera
        val otherCol = LinearLayout(this)
        otherCol.orientation = LinearLayout.VERTICAL
        otherCol.gravity = Gravity.CENTER_HORIZONTAL

        otherCol.addView(sectionHeading("Or use your phone"), LinearLayout.LayoutParams(MATCH, WRAP))
        val phoneText = TextView(this)
        phoneText.text = "Scan this code with your phone's camera, then paste the album link " +
            "on the page that opens. Your phone has to be on the same Wi-Fi."
        phoneText.setTextColor(0xFFD2D2D2.toInt())
        phoneText.typeface = Ui.medium(this)
        phoneText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        phoneText.gravity = Gravity.CENTER_HORIZONTAL
        phoneText.setLineSpacing(Ui.dp(this, 3f).toFloat(), 1f)
        otherCol.addView(phoneText, below(8f))

        val qrSide = Ui.dp(this, 248f)
        val qrPad = Ui.dp(this, 16f)
        val qrView = ImageView(this)
        qrView.background = Ui.roundRect(Color.WHITE, Ui.dp(this, 16f))
        qrView.setPadding(qrPad, qrPad, qrPad, qrPad)
        qrView.visibility = View.INVISIBLE
        otherCol.addView(
            qrView,
            LinearLayout.LayoutParams(qrSide + 2 * qrPad, qrSide + 2 * qrPad).apply {
                topMargin = Ui.dp(this@PhotosActivity, 16f)
            },
        )
        loadHandoffQr(qrView, phoneText, 0)

        val manualHeading = sectionHeading("Or enter the link here")
        otherCol.addView(manualHeading, below(36f))

        val edit = Ui.field(this, "Paste a Google Photos or iCloud link")
        edit.setSingleLine(true)
        edit.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        otherCol.addView(edit)
        val done = pillButton("Done", Ui.BLUE, 0xFFF0F0F0.toInt()) { addTypedAlbum(edit) }
        val doneLp = LinearLayout.LayoutParams(WRAP, WRAP)
        doneLp.topMargin = Ui.dp(this, 14f)
        doneLp.gravity = Gravity.END
        done.layoutParams = doneLp
        otherCol.addView(done)

        val gap = Ui.dp(this, 72f)
        page.addView(scanCol, LinearLayout.LayoutParams(colW, WRAP))
        page.addView(
            otherCol,
            LinearLayout.LayoutParams(colW, WRAP).apply {
                if (landscape) {
                    leftMargin = gap
                } else {
                    topMargin = Ui.dp(this@PhotosActivity, 40f)
                }
            },
        )
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))

        startCamera()
    }

    /**
     * Show the hand-off QR once the LAN server has bound its port. It carries the same token
     * as the photo-drop QR; `a=1` makes the page open on its "add an album" part.
     */
    private fun loadHandoffQr(
        view: ImageView,
        text: TextView,
        attempt: Int,
    ) {
        if (isFinishing) return
        val url = DropServerStatus.url(this)
        if (url == null) {
            if (attempt < 24) {
                main.postDelayed({ loadHandoffQr(view, text, attempt + 1) }, 250)
            } else {
                text.text =
                    if (DropServerStatus.lanIp() == null) {
                        "Connect the frame to Wi-Fi to add an album from your phone."
                    } else {
                        "The phone hand-off isn't available right now."
                    }
            }
            return
        }
        val worker =
            Thread({
                val bmp = QrCodes.bitmap("$url&a=1", 496)
                main.post {
                    if (bmp != null && !isFinishing) {
                        view.setImageBitmap(bmp)
                        view.visibility = View.VISIBLE
                    }
                }
            }, "handoff-qr")
        worker.isDaemon = true
        worker.start()
    }

    // ------------------------------------------------------ camera: Camera2 first

    /** Full-width, wrap-height layout params with a top margin in dp. */
    private fun below(dp: Float) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Ui.dp(this@PhotosActivity, dp) }

    private fun startCamera() {
        val vp = viewport ?: return
        // Testing aid: `--es cam legacy` or `--es cam camera2` overrides the device profile.
        val force = intent?.getStringExtra("cam") ?: ""
        if (force == "camera2" || (tuning.camera2 && force != "legacy")) {
            val view = ViewfinderView(this)
            vp.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            viewfinder = view
            val cam = QrCamera(this, cam2Listener)
            if (cam.start()) {
                cam2 = cam
                shapeViewport(cam.width, cam.height)
                scanning = true
                statsAt = System.currentTimeMillis()
                return
            }
            vp.removeView(view)
            viewfinder = null
        }
        startLegacyCamera()
    }

    /** Give the viewfinder box the camera's own shape, so the picture fills it. */
    private fun shapeViewport(
        w: Int,
        h: Int,
    ) {
        val vp = viewport ?: return
        val lp = vp.layoutParams ?: return
        if (w <= 0 || h <= 0 || lp.width <= 0) return
        lp.height = lp.width * h / w
        vp.layoutParams = lp
    }

    private val cam2Listener =
        object : QrCamera.Listener {
            override fun onFrame(frame: Luma) {
                if (!scanning) return
                val t0 = System.nanoTime()
                val r = search.scan(frame)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val now = System.currentTimeMillis()
                if (dumpLeft > 0) {
                    dumpLeft--
                    dumpFrame(frame, 0)
                }
                statsFrames++
                statsMs += ms
                statsPasses += r.passes
                if (r.text != null) {
                    scanning = false
                    Log.i(TAG, "QR read by ${r.pass} in $ms ms")
                    main.post { onQr(r.text) }
                    return
                }
                val roi = r.roi
                if (roi != null) {
                    // Seen but not read yet: box it and tell the user what helps.
                    if (lastSeenMs == 0L) {
                        seenSinceMs = now
                        Log.i(TAG, "QR located by ${r.pass}, ${roi.w}x${roi.h} at ${roi.left},${roi.top}")
                    }
                    lastSeenMs = now
                    val closer = now - seenSinceMs > CLOSER_AFTER_MS
                    main.post {
                        viewfinder?.setBox(roi, frame.w, frame.h)
                        if (scanning) {
                            scanHint?.text =
                                if (closer) {
                                    "Almost. Move the code a little closer to the camera and hold it still."
                                } else {
                                    "Found it, hold still…"
                                }
                        }
                    }
                } else if (lastSeenMs != 0L && now - lastSeenMs > SEEN_HOLD_MS) {
                    lastSeenMs = 0L
                    main.post {
                        viewfinder?.setBox(null, 0, 0)
                        if (scanning) scanHint?.text = DEFAULT_SCAN_HINT
                    }
                }
                if (now - statsAt >= STATS_EVERY_MS) {
                    Log.i(
                        TAG,
                        "QR scan: " + statsFrames * 1000 / (now - statsAt) + " frames/s decoded, " +
                            statsMs / Math.max(1, statsFrames) + " ms and " +
                            statsPasses / Math.max(1, statsFrames) + " passes per frame, no QR decoded yet",
                    )
                    statsAt = now
                    statsFrames = 0
                    statsMs = 0
                    statsPasses = 0
                }
            }

            override fun onViewfinder(frame: Luma) {
                val bmp = ViewfinderView.toBitmap(frame)
                main.post { viewfinder?.setFrame(bmp) }
            }

            override fun onFailed(why: String) {
                main.post {
                    if (cam2 == null || isFinishing) return@post
                    Log.i(TAG, "QR cam2 gave up ($why); falling back to the legacy camera")
                    cam2?.stop()
                    cam2 = null
                    viewfinder?.let { viewport?.removeView(it) }
                    viewfinder = null
                    startLegacyCamera()
                }
            }
        }

    /** Testing aid (`--ei dump N`): write frames as PGM into the app's external files folder. */
    private fun dumpFrame(
        frame: Luma,
        step: Int,
    ) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val f = File(dir, "qr-" + System.currentTimeMillis() + "-step" + step + ".pgm")
            f.outputStream().use {
                it.write("P5\n${frame.w} ${frame.h}\n255\n".toByteArray())
                it.write(frame.data, 0, frame.w * frame.h)
            }
            Log.i(TAG, "QR dump: $f")
        } catch (e: Exception) {
            Log.w(TAG, "QR dump failed", e)
        }
    }

    // ------------------------------------------------------ camera: legacy fallback

    private fun startLegacyCamera() {
        val vp = viewport ?: return
        val surface = SurfaceView(this)
        this.surface = surface
        vp.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH).apply { gravity = Gravity.CENTER })
        legacyWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "qr-decode").apply { isDaemon = true } }
        surface.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    openCamera(h)
                }

                override fun surfaceChanged(
                    h: SurfaceHolder,
                    fmt: Int,
                    w: Int,
                    height: Int,
                ) {
                }

                override fun surfaceDestroyed(h: SurfaceHolder) {
                    stopCamera()
                }
            },
        )
    }

    private fun openCamera(holder: SurfaceHolder) {
        try {
            val id = pickCamera()
            cameraId = id
            val camera = Camera.open(id)
            this.camera = camera
            val pr = camera.parameters

            // Largest supported preview (cap ~1920 wide to bound per-frame CPU) — more pixels
            // give a small/distant QR enough modules to decode on this fixed-focus camera.
            var best: Camera.Size? = null
            for (s in pr.supportedPreviewSizes) {
                if (s.width <= 1920 && (best == null || s.width > best.width)) {
                    best = s
                }
            }
            if (best != null) {
                pr.setPreviewSize(best.width, best.height)
            }
            val previewSize = best
            pr.previewFormat = ImageFormat.NV21
            // Portal Go's front camera is fixed-focus (HAL confirms focus-mode-values="fixed",
            // afAvailableModes=[OFF]), hyperfocal ~1.16m, and ultra-wide (~96° hFOV) at only
            // 1280x720 — so a QR aimed at the box lands in the centre as just a few dozen pixels.
            //
            // Zoom does double duty here. Obviously it enlarges the QR. Less obviously it fixes the
            // exposure: at low zoom the dark room fills most of this ultra-wide frame, so the AE
            // brightens for the room and blows the screen out. Zoom in hard and the bright screen
            // *fills the frame*, so the AE meters the screen and exposes down — the one lever that
            // actually moves exposure on this HAL (EV is ignored). The previewCb runs a closed loop
            // on top of this; here we just set the starting zoom. zoompct pins it (testing).
            if (pr.isZoomSupported) {
                maxZoomIdx = pr.maxZoom
                // A `zoompct` intent extra overrides the profile (testing); otherwise the device's
                // QrTuning decides: a pinned fixed zoom (Portal Go) or the closed-loop's start (others).
                val override = intent?.getIntExtra("zoompct", 0) ?: 0
                val pinPct = if (override > 0) override else tuning.fixedZoomPct
                if (pinPct > 0) {
                    autoExpZoom = false // pinned level — the closed loop in previewCb stays off
                    curZoom = Math.max(0, Math.min(maxZoomIdx, maxZoomIdx * pinPct / 100))
                } else if (tuning.loopStartZoomPct > 0) {
                    autoExpZoom = true
                    // Start fairly tight: a bright screen needs heavy fill to expose down, and the
                    // loop backs the zoom off again if that overshoots too dark.
                    curZoom = Math.max(0, Math.min(maxZoomIdx, maxZoomIdx * tuning.loopStartZoomPct / 100))
                } else {
                    autoExpZoom = false // this device's zoom does nothing: leave it alone
                    curZoom = 0
                }
                pr.zoom = curZoom
                zoomSettle = AE_SETTLE_FRAMES
            }
            // Force the highest fixed frame rate the camera offers. A fixed high FPS caps the
            // exposure *time* the AE is allowed to integrate, so it physically can't over-expose
            // the bright screen by holding the shutter open for the dark room. (Tunable: hifps=0
            // leaves the default auto range.)
            if (intent?.getIntExtra("hifps", 1) != 0) {
                val ranges = pr.supportedPreviewFpsRange // each is [min,max] in fps*1000
                // Prefer the range with the highest minimum (most aggressive exposure-time cap),
                // breaking ties on the highest max.
                val best =
                    ranges?.maxWithOrNull(
                        compareBy({ it[0] }, { it[1] }),
                    )
                if (best != null) {
                    pr.setPreviewFpsRange(best[0], best[1])
                }
                Log.i(TAG, "QR cam fps ranges=" + ranges?.joinToString { it.contentToString() })
            }
            // Exposure controls: bias dark so the bright phone screen doesn't blow out as the room
            // (now larger in the wider, low-zoom frame) pulls the AE brighter. Measured safe here —
            // EV -9 left luma mean ~75 (not crushed). Barcode scene mode is preferred when offered
            // (it drives metering itself, so it's used alone); otherwise centre-meter + bias dark.
            val sceneModes = pr.supportedSceneModes
            val usingBarcodeScene =
                sceneModes?.contains(Camera.Parameters.SCENE_MODE_BARCODE) == true
            if (usingBarcodeScene) {
                pr.sceneMode = Camera.Parameters.SCENE_MODE_BARCODE
            } else {
                if (pr.maxNumMeteringAreas >= 1) {
                    val areas = ArrayList<Camera.Area>()
                    areas.add(Camera.Area(Rect(-350, -350, 350, 350), 1000))
                    pr.meteringAreas = areas
                }
                if (pr.minExposureCompensation < 0) {
                    pr.exposureCompensation = pr.minExposureCompensation
                }
            }
            // Phone displays flicker (PWM dimming / panel refresh); let the camera cancel the
            // rolling bands that would otherwise streak across — and corrupt — the code. Harmless
            // alongside either path above (the HAL ignores it if a scene mode already drives it).
            val antibanding = pr.supportedAntibanding
            if (antibanding?.contains(Camera.Parameters.ANTIBANDING_AUTO) == true) {
                pr.antibanding = Camera.Parameters.ANTIBANDING_AUTO
            }

            // The Portal Go camera is genuinely fixed-focus — focus-mode-values reports only
            // "fixed", so none of the autofocus modes below are ever offered. We still probe for
            // them defensively (other Portal models / future firmware may differ); on Go this
            // when-block is a no-op and focus stays at the lens's hyperfocal (~1.16m).
            val focusModes = pr.supportedFocusModes
            when {
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true ->
                    pr.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true ->
                    pr.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true ->
                    pr.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            camera.parameters = pr
            Log.i(
                TAG,
                "QR cam caps: focusModes=" + focusModes + " using=" + pr.focusMode +
                    " sceneMode=" + pr.sceneMode + " ev=" + pr.exposureCompensation +
                    "/[" + pr.minExposureCompensation + ".." + pr.maxExposureCompensation + "]" +
                    " antibanding=" + pr.antibanding +
                    " preview=" + pr.previewSize.width + "x" + pr.previewSize.height + " maxPicture=" +
                    pr.supportedPictureSizes.maxByOrNull { it.width * it.height }
                        ?.let { "${it.width}x${it.height}" } +
                    " hFov=" + pr.horizontalViewAngle + " zoom=" + pr.zoom + "/" + pr.maxZoom +
                    " fpsRange=" + IntArray(2).also { pr.getPreviewFpsRange(it) }.contentToString(),
            )

            val ps = camera.parameters.previewSize
            previewW = ps.width
            previewH = ps.height
            frameCount = 0

            // Orient the *preview* upright for however the Portal screen is mounted (Portal+ can
            // be vertical/portrait, Portal Go is landscape). Feedback only — decode reads raw NV21
            // (sensor-native, unaffected) and tries every rotation — but a sideways, squashed
            // preview makes the QR impossible to aim, so match the display and fit the aspect.
            val orientation = previewDisplayOrientation(id)
            camera.setDisplayOrientation(orientation)
            if (previewSize != null) {
                shapeViewport(previewSize.width, previewSize.height)
                sizePreviewToAspect(previewSize.width, previewSize.height, orientation)
            }
            camera.setPreviewDisplay(holder)
            scanning = true
            camera.setPreviewCallback(previewCb)
            camera.startPreview()
            Log.i(
                TAG,
                "camera opened id=" + id + " preview=" + previewW + "x" + previewH +
                    " zoomSupported=" + pr.isZoomSupported,
            )
        } catch (e: Exception) {
            Log.e(TAG, "camera open failed", e)
            toast("Couldn't open the camera")
            finish()
        }
    }

    /**
     * Degrees to rotate the camera preview so it appears upright on the current display, from the
     * device's rotation and the camera's mounted sensor orientation (standard Android formula).
     * Front-facing cameras are mirrored by the framework, so they subtract rather than add.
     */
    private fun previewDisplayOrientation(cameraId: Int): Int {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        val degrees =
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + degrees) % 360) % 360
        } else {
            (info.orientation - degrees + 360) % 360
        }
    }

    /**
     * Size the preview SurfaceView to the camera's aspect ratio, FITTING the whole frame inside
     * the viewfinder box (letterboxed) rather than cropping to fill. Fitting keeps the field of view
     * identical in portrait and landscape — covering would center-crop the 16:9 sensor frame much
     * harder on a portrait screen, making it look zoomed in vertical though the camera isn't. A
     * 90°/270° rotation swaps width/height. Decode is unaffected — it reads the raw frame.
     */
    private fun sizePreviewToAspect(
        previewW: Int,
        previewH: Int,
        orientation: Int,
    ) {
        val s = surface ?: return
        val rotated = orientation == 90 || orientation == 270
        val aspectW = if (rotated) previewH else previewW
        val aspectH = if (rotated) previewW else previewH
        if (aspectW == 0 || aspectH == 0) return
        val vp = viewport ?: return
        // The box's requested size: it may just have been reshaped and not laid out yet.
        val boxW = if (vp.layoutParams.width > 0) vp.layoutParams.width else vp.width
        val boxH = if (vp.layoutParams.height > 0) vp.layoutParams.height else vp.height
        val scale = Math.min(boxW.toFloat() / aspectW, boxH.toFloat() / aspectH)
        s.layoutParams =
            FrameLayout.LayoutParams(
                (aspectW * scale).toInt(),
                (aspectH * scale).toInt(),
            ).apply { gravity = Gravity.CENTER }
        s.requestLayout()
    }

    private val previewCb =
        Camera.PreviewCallback { data, _ ->
            if (scanning && data != null && previewW != 0) {
                frameCount++
                if (frameCount == 1) {
                    Log.i(
                        TAG,
                        "QR scan: first frame " + previewW + "x" + previewH +
                            " bytes=" + data.size,
                    )
                }
                // Closed-loop exposure: nudge the zoom toward a target brightness so the AE exposes the
                // bright screen down out of clipping. Hold a few frames after each change for the AE to
                // re-settle before judging again. While a code is in sight the zoom stays put: moving
                // it would change the code's size and place just as the decoder is closing in.
                val seen = lastSeenMs != 0L && System.currentTimeMillis() - lastSeenMs < SEEN_HOLD_MS
                if (autoExpZoom && !seen) {
                    if (zoomSettle > 0) {
                        zoomSettle--
                    } else {
                        val luma = centerLuma(data, previewW, previewH)
                        // Act only on a reading that persists, so one bright frame cannot start a swing.
                        val want =
                            when {
                                luma > EXP_BRIGHT && curZoom < maxZoomIdx -> 1
                                luma < EXP_DARK && curZoom > 0 -> -1
                                else -> 0
                            }
                        zoomVotes = if (want != 0 && want == zoomWant) zoomVotes + 1 else 0
                        zoomWant = want
                        if (want != 0 && zoomVotes >= ZOOM_VOTES) {
                            curZoom = Math.max(0, Math.min(maxZoomIdx, curZoom + want * zoomStep()))
                            applyZoom(curZoom)
                            search.reset()
                            zoomSettle = AE_SETTLE_FRAMES
                            zoomVotes = 0
                        }
                        if (frameCount % 30 == 1) {
                            Log.i(TAG, "QR exp: centerLuma=$luma zoom=$curZoom/$maxZoomIdx")
                        }
                    }
                }
                // Decode on a worker, one frame at a time; frames that arrive meanwhile are skipped,
                // so the camera (and the zoom loop above) keeps its full rate.
                val worker = legacyWorker
                if (worker != null && legacyBusy.compareAndSet(false, true)) {
                    val n = previewW * previewH
                    var buf = legacyBuf
                    if (buf == null || buf.size != n) {
                        buf = ByteArray(n)
                        legacyBuf = buf
                    }
                    System.arraycopy(data, 0, buf, 0, Math.min(n, data.size))
                    val frame = Luma(buf, previewW, previewH)
                    try {
                        worker.execute {
                            try {
                                if (dumpLeft > 0) {
                                    dumpLeft--
                                    dumpFrame(frame, curZoom)
                                }
                                val r = search.scan(frame)
                                if (r.text != null && scanning) {
                                    scanning = false // stop processing further frames until we decide
                                    main.post { onQr(r.text) }
                                } else if (r.roi != null) {
                                    lastSeenMs = System.currentTimeMillis()
                                }
                            } finally {
                                legacyBusy.set(false)
                            }
                        }
                    } catch (e: Exception) {
                        legacyBusy.set(false) // worker already shut down
                    }
                }
                if (frameCount % 150 == 1) {
                    Log.i(TAG, "QR scan: " + frameCount + " frames, no QR decoded yet")
                }
            }
        }

    /** Re-apply just the zoom level mid-stream (cheap next to a full camera reconfigure). */
    private fun applyZoom(z: Int) {
        try {
            val c = camera ?: return
            val p = c.parameters
            if (p.isZoomSupported && p.zoom != z) {
                p.zoom = z
                c.parameters = p
            }
        } catch (ignored: Exception) {
            // best-effort; a dropped zoom step just retries on the next cycle
        }
    }

    /** One exposure-loop zoom step ≈ 10% of the zoom range. */
    private fun zoomStep(): Int = Math.max(1, maxZoomIdx / 10)

    /**
     * Mean luma of the central half of the NV21 frame (where the centred QR/screen sits), sampled
     * sparsely. Drives the exposure loop: this region tracks the screen, so a high value means the
     * screen is blown out and the zoom should fill more to make the AE expose down.
     */
    private fun centerLuma(
        data: ByteArray,
        w: Int,
        h: Int,
    ): Int {
        val x0 = w / 4
        val x1 = w * 3 / 4
        val y0 = h / 4
        val y1 = h * 3 / 4
        var sum = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val row = y * w
            var x = x0
            while (x < x1) {
                sum += (data[row + x].toInt() and 0xFF)
                n++
                x += 8
            }
            y += 8
        }
        return if (n > 0) (sum / n).toInt() else 0
    }

    private fun onQr(text: String?) {
        val url = text?.trim() ?: ""
        if (!isPhotosLink(url)) {
            scanHint?.text = "That QR isn't a Google Photos or iCloud album link — try again"
            viewfinder?.setBox(null, 0, 0)
            scanning = true // keep scanning
            return
        }
        Albums.add(prefs(), url)
        Log.i(TAG, "album added via QR: $url")
        stopCamera()
        scanHint?.text = "Album set ✓ — your photos will appear shortly"
        toast("Album set ✓")
        main.postDelayed({ finish() }, 1500)
    }

    private fun stopCamera() {
        scanning = false
        cam2?.stop()
        cam2 = null
        legacyWorker?.shutdownNow()
        legacyWorker = null
        camera?.let { c ->
            try {
                c.setPreviewCallback(null)
                c.stopPreview()
                c.release()
            } catch (ignored: Exception) {
                // best-effort teardown
            }
            camera = null
        }
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "Lumaframe"
        private const val REQ_CAMERA = 1
        private const val AE_SETTLE_FRAMES = 3 // frames to let the AE settle after a zoom change
        private const val EXP_BRIGHT = 165 // center luma above this = blown out → zoom in (AE down)
        private const val EXP_DARK = 95 // center luma below this = too dark → zoom out
        private const val ZOOM_VOTES = 3 // consecutive out-of-range readings before the zoom moves
        private const val SEEN_HOLD_MS = 2000L // keep exposure/zoom this long after a code was last seen
        private const val STATS_EVERY_MS = 5000L
        private const val CLOSER_AFTER_MS = 2500L // located this long without a read: ask to come closer
        private const val DEFAULT_SCAN_HINT =
            "Hold the QR code up to the camera above the screen, about a forearm's length " +
                "(30 cm) away, and keep it still for a moment."

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private fun pickCamera(): Int {
            val n = Camera.getNumberOfCameras()
            val info = Camera.CameraInfo()
            for (i in 0 until n) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    return i
                }
            }
            return 0 // Portal has only a front camera, which faces the user holding the phone
        }

        private fun isPhotosLink(s: String): Boolean = PhotoSources.matches(s)
    }
}
