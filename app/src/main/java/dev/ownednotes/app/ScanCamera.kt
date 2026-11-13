package dev.ownednotes.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * P247: the scan screen's live camera, with the framework's Camera2 (no camera library). The back camera fills [view]
 * like a camera app (cropped, never stretched), a small brightness frame is searched for a page about ten times a second
 * ([onCorners], in the shown picture's 0–1 units, or null), and [takePicture] takes a full photo the way the screen is
 * turned. [torch] lights the page. Nothing leaves the tablet. Open it while the screen shows and close it when the app
 * goes to the background; a closed camera is not reused.
 */
internal class ScanCamera(
    private val context: Context,
    private val view: TextureView,
    private val onCorners: (FloatArray?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val id = pick(manager)
    private val traits = id?.let { manager.getCameraCharacteristics(it) }
    private val sensor = traits?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    private val map = traits?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    private val previewSize = choose(map?.getOutputSizes(SurfaceTexture::class.java), 1920)
    private val analysisSize = choose(map?.getOutputSizes(ImageFormat.YUV_420_888), 800)
    private val jpegSize = choose(map?.getOutputSizes(ImageFormat.JPEG), 4032)
    private val focus = traits?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let { modes ->
        listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureRequest.CONTROL_AF_MODE_AUTO).firstOrNull { it in modes }
    }
    /** False on tablets without a flash: the Flash button is hidden. */
    val hasFlash = traits?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    /** The picture shown: what [view] maps from the camera's upright frame, after [transform]. */
    private val shown = Matrix()
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val display = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) = transform()
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    // Camera thread state: set and read on [handler] only, except [opened], which the main thread flips.
    @Volatile private var opened = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var analysis: ImageReader? = null
    private var jpeg: ImageReader? = null
    private var shot: ((Result<ByteArray>) -> Unit)? = null
    private var lastLook = 0L
    @Volatile var torch = false; private set
    /** True once the first frame has been searched: the picture is live. */
    @Volatile var started = false; private set

    fun open() {
        if (opened || id == null) { if (id == null) onError("This device has no camera"); return }
        opened = true
        val t = HandlerThread("scan-camera").apply { start() }; thread = t
        val h = Handler(t.looper); handler = h
        analysis = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2).apply { setOnImageAvailableListener({ look(it) }, h) }
        jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply { setOnImageAvailableListener({ took(it) }, h) }
        displays.registerDisplayListener(display, main)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) = start(texture)
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = transform()
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        view.surfaceTexture?.takeIf { view.isAvailable }?.let { start(it) }
    }

    /** Stops the camera and its thread; a photo being taken is reported as failed. */
    fun close() {
        if (!opened) return
        opened = false; started = false
        displays.unregisterDisplayListener(display)
        view.surfaceTextureListener = null
        val h = handler ?: return; val t = thread
        handler = null; thread = null
        h.post {
            shot?.let { done -> shot = null; main.post { done(Result.failure(IllegalStateException("The camera closed"))) } }
            runCatching { session?.close() }; session = null
            // A camera still opening is closed by its own callback, which also ends the thread.
            val d = device; device = null
            if (d != null || !opening) { runCatching { d?.close() }; release(); t?.quitSafely() }
        }
    }
    private var opening = false
    private fun release() { analysis?.close(); jpeg?.close(); surface?.release(); analysis = null; jpeg = null; surface = null }

    fun setTorch(on: Boolean) { torch = on; handler?.post { runCatching { repeat() } } }

    /** Takes a full photo; [done] runs on the main thread with the JPEG bytes. */
    fun takePicture(done: (Result<ByteArray>) -> Unit) {
        val h = handler ?: return done(Result.failure(IllegalStateException("The camera is not open")))
        val turn = (sensor - rotation() + 360) % 360
        h.post {
            val s = session; val d = device; val out = jpeg; val preview = surface
            if (s == null || d == null || out == null || preview == null || shot != null) {
                main.post { done(Result.failure(IllegalStateException("The camera is not ready"))) }; return@post
            }
            shot = done
            runCatching {
                val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(out.surface); addTarget(preview); settings(this)
                    set(CaptureRequest.JPEG_ORIENTATION, turn); set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                }
                s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) = missed(done)
                }, h)
                h.postDelayed({ missed(done) }, 8000)
            }.onFailure { missed(done) }
        }
    }
    private fun missed(done: (Result<ByteArray>) -> Unit) {
        if (shot !== done) return
        shot = null; main.post { done(Result.failure(IllegalStateException("The camera did not take the photo"))) }
    }

    /** Camera picture 0–1 units ([onCorners]) to [view] pixels, as x, y pairs. */
    fun toView(corners: FloatArray): FloatArray {
        val out = FloatArray(corners.size) { if (it % 2 == 0) corners[it] * view.width else corners[it] * view.height }
        shown.mapPoints(out); return out
    }

    private fun start(texture: SurfaceTexture) {
        if (!opened || surface != null) return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        surface = Surface(texture)
        transform()
        val h = handler ?: return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return onError("Allow the camera to scan")
        h.post {
            opening = true
            runCatching { manager.openCamera(id!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (!opened) { camera.close(); release(); h.looper.quitSafely(); return }
                    device = camera; configure(camera, h)
                }
                override fun onDisconnected(camera: CameraDevice) { opening = false; camera.close(); if (device === camera) device = null; fail("The camera is in use by another app") }
                override fun onError(camera: CameraDevice, error: Int) { opening = false; camera.close(); if (device === camera) device = null; fail("The camera stopped (error $error)") }
            }, h) }.onFailure { opening = false; fail("Cannot open the camera: ${it.message}") }
        }
    }

    private fun configure(camera: CameraDevice, h: Handler) {
        val preview = surface ?: return; val a = analysis ?: return; val j = jpeg ?: return
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(a.surface), OutputConfiguration(j.surface))
        runCatching {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, Executor { h.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (!opened || device !== camera) { s.close(); return }
                        session = s; runCatching { repeat() }.onFailure { fail("Cannot start the camera: ${it.message}") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) = fail("Cannot start the camera")
                }))
        }.onFailure { fail("Cannot start the camera: ${it.message}") }
    }

    /** The live picture, with the torch as set. */
    private fun repeat() {
        val s = session ?: return; val d = device ?: return; val preview = surface ?: return; val a = analysis ?: return
        s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(preview); addTarget(a.surface); settings(this) }.build(), null, handler)
    }
    private fun settings(request: CaptureRequest.Builder) {
        focus?.let { request.set(CaptureRequest.CONTROL_AF_MODE, it) }
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        request.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    /** About ten times a second: the page's corners in the small brightness frame, turned upright, in 0–1 units. */
    private fun look(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val now = SystemClock.uptimeMillis()
        val corners = image.use { img ->
            if (now - lastLook < 90) return
            lastLook = now
            runCatching {
                val plane = img.planes[0]; val buffer = plane.buffer; val row = plane.rowStride; val px = plane.pixelStride
                val step = max(1, ceil(max(img.width, img.height) / 160f).toInt())
                val w = img.width / step; val h = img.height / step
                val lum = IntArray(w * h) { i -> buffer.get((i / w) * step * row + (i % w) * step * px).toInt() and 0xff }
                ScanProcessing.detectLuma(lum, w, h)?.let { c -> upright(FloatArray(8) { if (it % 2 == 0) c[it] / w else c[it] / h }) }
                    // Nearly the whole picture is a bright room (a pale wall and floor), not a page held in view.
                    ?.takeIf { c -> val p = points(c); ScanProcessing.convex(p) && ScanProcessing.area(p) in .1f..MOST }
            }.getOrNull()
        }
        main.post { if (opened) { started = true; onCorners(corners) } }
    }
    private fun took(reader: ImageReader) {
        val bytes = runCatching { reader.acquireNextImage()?.use { img -> img.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } } } }.getOrNull()
        val done = shot ?: return
        shot = null
        main.post { done(if (bytes != null) Result.success(bytes) else Result.failure(IllegalStateException("The camera returned no photo"))) }
    }
    private fun fail(text: String) { main.post { if (opened) onError(text) } }

    /** Sensor-frame 0–1 corners turned the way the camera is mounted, so they match the upright picture. */
    private fun upright(c: FloatArray) = FloatArray(8).also { out ->
        for (i in 0 until 4) {
            val u = c[i * 2]; val v = c[i * 2 + 1]
            val (x, y) = when (sensor) { 90 -> 1 - v to u; 180 -> 1 - u to 1 - v; 270 -> v to 1 - u; else -> u to v }
            out[i * 2] = x; out[i * 2 + 1] = y
        }
    }

    /**
     * The camera hands [view] its picture upright for the tablet's natural way up, stretched to the view; this undoes the
     * stretch, turns it with the screen and scales it to fill, cropping the overflow, as camera apps do.
     */
    private fun transform() {
        val vw = view.width.toFloat(); val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val turn = rotation()
        val natW = (if (sensor % 180 == 0) previewSize.width else previewSize.height).toFloat()
        val natH = (if (sensor % 180 == 0) previewSize.height else previewSize.width).toFloat()
        val cw = if (turn % 180 == 0) natW else natH; val ch = if (turn % 180 == 0) natH else natW
        val fill = max(vw / cw, vh / ch)
        shown.reset()
        shown.postScale(natW / vw, natH / vh); shown.postTranslate(-natW / 2, -natH / 2)
        shown.postRotate(-turn.toFloat()); shown.postScale(fill, fill); shown.postTranslate(vw / 2, vh / 2)
        view.setTransform(shown)
    }
    private fun rotation() = (view.display?.rotation ?: Surface.ROTATION_0) * 90

    companion object {
        /** True when the tablet has a camera the scan screen can use. */
        fun available(context: Context) = runCatching { pick(context.getSystemService(CameraManager::class.java)) != null }.getOrDefault(false)

        private fun pick(manager: CameraManager): String? = runCatching {
            val ids = manager.cameraIdList
            fun facing(f: Int) = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == f }
            facing(CameraCharacteristics.LENS_FACING_BACK) ?: facing(CameraCharacteristics.LENS_FACING_EXTERNAL) ?: ids.firstOrNull()
        }.getOrNull()

        /** The largest 4:3 size with its long edge within [longest], else the largest size that fits. */
        private fun choose(sizes: Array<Size>?, longest: Int): Size {
            val all = sizes.orEmpty().filter { max(it.width, it.height) <= longest }
            fun area(s: Size) = s.width.toLong() * s.height
            return all.filter { abs(max(it.width, it.height) * 3 - minOf(it.width, it.height) * 4) <= 8 }.maxByOrNull(::area)
                ?: all.maxByOrNull(::area) ?: sizes?.minByOrNull(::area) ?: Size(640, 480)
        }

        fun points(c: FloatArray) = (0 until 4).map { PointF(c[it * 2], c[it * 2 + 1]) }
        /** The most of the picture a page found live may cover. */
        private const val MOST = .8f
    }
}

/**
 * P247: when Auto takes the page. It fires once the page has been held still ([still], 0–1 units) for [hold] ms and fills
 * at least [minArea] of the picture, and then waits until that page leaves the picture or moves by [moved], so the same
 * page is not taken twice.
 */
internal class AutoCapture(
    private val hold: Long = 1200, private val still: Float = .03f, private val moved: Float = .15f, private val minArea: Float = .2f,
) {
    private var anchor: FloatArray? = null
    private var since = 0L
    private var taken: FloatArray? = null
    private var missing = 0
    /** True after a page was taken, until it leaves or moves: the screen says to turn to the next page. */
    val waiting get() = taken != null

    /** The hold so far, 0–1, for [corners] seen at [now]; 1 means take the page now. */
    fun update(corners: FloatArray?, now: Long): Float {
        if (corners == null || area(corners) < minArea) {
            anchor = null
            // A few empty frames in a row: the page has gone, so the next one may be taken.
            if (++missing >= 3) taken = null
            return 0f
        }
        missing = 0
        taken?.let { if (far(it, corners) > moved) taken = null else return 0f }
        val a = anchor
        if (a == null || far(a, corners) > still) { anchor = corners.copyOf(); since = now; return 0f }
        val progress = ((now - since).toFloat() / hold).coerceIn(0f, 1f)
        if (progress >= 1f) took(corners)
        return progress
    }
    /** A page was taken (by Auto or the shutter): wait for the next one. */
    fun took(corners: FloatArray?) { taken = corners?.copyOf(); anchor = null; missing = 0 }
    fun reset() { anchor = null; taken = null; missing = 0 }
    /** The hold starts again, as after a pause in looking; a page already taken stays taken. */
    fun restartHold() { anchor = null }

    private fun far(a: FloatArray, b: FloatArray) = (0 until 4).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }
    private fun area(c: FloatArray) = abs((0 until 4).sumOf { i -> val j = (i + 1) % 4; (c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1]).toDouble() }).toFloat() / 2
}
