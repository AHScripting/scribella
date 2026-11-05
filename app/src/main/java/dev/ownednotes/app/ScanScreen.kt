package dev.ownednotes.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.ownednotes.core.Notebook
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P201 step 10: the Scan screen, as on the Glass "Scan" board, in place of the scan dialog. Dark glass pills along the
 * top hold Cancel, the title and the photo buttons, with the status under them; the glass panel at the foot holds the
 * looks, where the pages will go, the stack of pages so far, the shutter and Add. Add puts every page into the notebook
 * after the page that was open, as one Undo step. The screen is dark by day and by night, like a camera.
 *
 * It shows one of three things:
 * - Live (P247, [ScanProcessing.live] with no [photo]): the in-app camera fills the screen ([ScanCamera]) with the page it
 *   finds outlined. The title pill carries the Auto switch, which takes the page once it is held still ([AutoCapture],
 *   "Page found — hold still" with a ring filling); the right pill has Flash and Import from Photos. The shutter takes a
 *   page with its corners found and the look chosen, and keeps it ([ScanProcessing.pages]).
 * - A photo with its corners ([ScanCropView]), from the system camera, a picture from Photos, or a page kept earlier
 *   ([review]). The shutter keeps it and goes on to the next page; Whole photo resets the corners.
 * - The pages kept so far (the system camera came back empty).
 *
 * P561: the stack opens a row of the pages ([openTray]), and any of them opens again on its photo with its corners and
 * look, as often as needed until Add. It keeps its place in the scan, and whatever was open is kept first.
 */
internal class ScanScreen(
    private val activity: MainActivity,
    private val repository: NotebookRepository,
    private val target: ImageInsertTarget,
    private val file: File?,
    private val photo: Bitmap?,
    detected: List<PointF>?,
    /** A page kept earlier, opened again from the stack: it keeps its place, and can also be removed. */
    private val review: ScanProcessing.KeptPage? = null,
) : FrameLayout(activity) {
    private val c = activity
    private val app = activity.application as NotesApplication
    /** True while the live camera fills the screen. */
    val live = photo == null && ScanProcessing.live
    internal val crop = photo?.let { ScanCropView(c, it, detected ?: ScanProcessing.inset(it)) }
    internal var look = review?.look ?: ScanProcessing.look; private set
    internal val looks: List<TextView> = LOOKS.map { (look, name) -> chip(name, look) }
    internal val cancel = pill("Cancel").apply { setOnClickListener { cancel() } }
    internal val whole = roundButton("Whole photo", WHOLE) { photo?.let { p -> crop?.let { it.setCorners(ScanProcessing.wholeLike(p, it.corners)) } } }
    /** P567: for a page photographed sideways or upside down. It stays with the page's corners, so a kept page keeps its turn. */
    internal val rotate = roundButton("Rotate page", ROTATE) { crop?.turnClockwise(); status.announceForAccessibility("Page rotated") }
    internal val photos = roundButton("Import from Photos", Kit.Paths.IMAGE) { keepThen { activity.pickScanPhoto() } }
    /** Review only: drops the page opened again and goes back to the camera, or to the pages left. */
    internal val remove = review?.let { page -> roundButton("Remove this page", TRASH) { removePage(page) } }
    internal val status = TextView(c)
    internal val destination = TextView(c)
    internal val stack = Stack()
    internal val shutter = Shutter()
    internal val add = TextView(c)
    /** The "Discard N scanned pages?" question Cancel asks once more than one page is waiting. */
    internal var confirm: AlertDialog? = null; private set
    /** Pages Add would put in: those kept, and this photo unless it is one of them. */
    val count get() = ScanProcessing.pages.size + (if (photo != null && review == null) 1 else 0)
    /** The row of pages, while open. */
    internal var tray: View? = null; private set
    /** The row's pages, in order. */
    internal val trayPages = mutableListOf<View>()
    private val detectedEdges = detected != null
    private var working = false
    private var closed = false
    private var preview: Bitmap? = null
    private var after: Bitmap? = null
    private var keptLarge: Bitmap? = null
    /** The straightened page in the chosen look, beside the photo while its corners are fitted; hidden when too narrow for both. */
    internal val result = photo?.let { ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }
    internal val resultPane = result?.let { pane("After", it) }
    private val previewLater = Runnable { updatePreview() }
    private val mark = StatusMark()

    // Live camera (P247).
    internal val viewfinder = if (live) TextureView(c).apply { contentDescription = "Camera view"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO } else null
    internal val edges = if (live) Edges() else null
    internal var camera: ScanCamera? = null; private set
    private val hasFlash = live && runCatching { ScanCamera(c, viewfinder!!, {}, {}).hasFlash }.getOrDefault(false)
    internal val autoCapture = AutoCapture()
    internal val auto = if (live) Kit.Switch(c, prefs().getBoolean(AUTO, true), "Auto scan", small = true, night = true) { on ->
        prefs().edit().putBoolean(AUTO, on).apply(); autoCapture.reset(); hold = 0f; show()
    } else null
    internal val flash = if (hasFlash) roundButton("Flash", FLASH) { setTorch(!torchOn) } else null
    internal var torchOn = false; private set
    /** The page the camera sees now, in the picture's 0–1 units, or null. */
    internal var seen: FloatArray? = null; private set
    private var hold = 0f
    private var cameraError: String? = null
    private val lifecycleWatch = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = startCamera()
        override fun onPause(owner: LifecycleOwner) = stopCamera()
    }

    init {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; colors = intArrayOf(0xff5b544b.toInt(), 0xff2b2723.toInt(), 0xff1a1715.toInt())
        }
        isClickable = true
        // The page: the live camera filling the screen, the photo with its corners, or the last page kept.
        val middle = LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(dpi(24f), dpi(146f), dpi(24f), dpi(280f)) }
        when {
            viewfinder != null -> { addView(viewfinder, LayoutParams(MATCH_PARENT, MATCH_PARENT)); addView(edges, LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            crop != null -> {
                crop.onCornersChanged = { show(); removeCallbacks(previewLater); postDelayed(previewLater, 120) }
                addView(LinearLayout(c).apply {
                    addView(pane("Before", crop), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                    addView(resultPane!!, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dpi(16f) })
                    addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
                        if (r - l != or_ - ol) v.post {
                            val both = r - l >= dpi(BOTH_WIDTH)
                            if (both != (resultPane.visibility == View.VISIBLE)) {
                                resultPane.visibility = if (both) View.VISIBLE else View.GONE
                                if (both) updatePreview()
                            }
                        }
                    }
                }, middle)
            }
            else -> addView(ImageView(c).apply {
                keptLarge = ScanProcessing.pages.lastOrNull()?.let { decodeKept(it.bytes) }
                setImageBitmap(keptLarge); scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Last page kept"
            }, middle)
        }
        addView(cancel, LayoutParams(WRAP_CONTENT, dpi(50f), Gravity.START or Gravity.TOP).apply { setMargins(dpi(32f), dpi(24f), 0, 0) })
        addView(title(), LayoutParams(WRAP_CONTENT, dpi(50f), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dpi(24f) })
        addView(LinearLayout(c).apply {
            background = darkGlass(25f); elevation = dp(8f); gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(3f), 0, dpi(3f), 0)
            (if (live) listOfNotNull(flash, photos) else listOfNotNull(rotate, whole, photos, remove)).forEach { addView(it, LinearLayout.LayoutParams(dpi(44f), dpi(44f))) }
        }, LayoutParams(WRAP_CONTENT, dpi(50f), Gravity.END or Gravity.TOP).apply { setMargins(0, dpi(24f), dpi(32f), 0) })
        status.apply {
            background = darkGlass(22f); elevation = dp(8f); gravity = Gravity.CENTER_VERTICAL
            textSize = 14f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = dpi(10f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(status, LayoutParams(WRAP_CONTENT, dpi(44f), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dpi(88f) })
        addView(panel(), LayoutParams(MATCH_PARENT, dpi(236f), Gravity.BOTTOM).apply { setMargins(dpi(24f), 0, dpi(24f), dpi(28f)) })
        if (live) activity.lifecycle.addObserver(lifecycleWatch)
        updatePreview(); show()
    }

    /** Leaves the screen, discarding every page not yet added; asks first when more than one would be lost. */
    fun cancel() {
        if (tray != null) { closeTray(); return }
        if (count <= 1) { activity.closeScan(); return }
        confirm = AlertDialog.Builder(c).setTitle("Discard $count scanned pages?").setMessage("None of them has been added to the notebook yet.")
            .setPositiveButton("Discard") { _, _ -> activity.closeScan() }.setNegativeButton("Keep scanning", null)
            .setOnDismissListener { confirm = null }.show()
    }

    /** Called once the screen has left the window: the camera is closed, the photo and previews freed, the capture deleted. */
    fun dispose() {
        closed = true; removeCallbacks(previewLater); confirm?.dismiss()
        if (live) { activity.lifecycle.removeObserver(lifecycleWatch); stopCamera() }
        result?.setImageDrawable(null)
        file?.delete(); preview?.recycle(); after?.recycle(); keptLarge?.recycle()
        if (!working) photo?.recycle()
    }

    /** The title pill; live, it carries the Auto switch in its own chip, as on the board. */
    private fun title(): View {
        val name = pill("Scan document").apply { isClickable = false; isAccessibilityHeading = true; textSize = 15.5f }
        val auto = auto ?: return name
        return LinearLayout(c).apply {
            background = darkGlass(25f); elevation = dp(8f); gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(22f), 0, dpi(8f), 0)
            addView(name.apply { background = null; elevation = 0f; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            addView(LinearLayout(c).apply {
                background = rounded(0x24ffffff, dp(17f)); gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(12f), 0, dpi(6f), 0)
                addView(TextView(c).apply {
                    text = "Auto"; textSize = 13f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(auto, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(8f) })
                // The whole chip flips the switch, a bigger target than the switch alone.
                setOnClickListener { auto.performClick() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(34f)).apply { marginStart = dpi(14f) })
        }
    }

    private fun panel() = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; background = darkGlass(40f); elevation = dp(12f)
        setPadding(dpi(30f), dpi(18f), dpi(30f), dpi(26f))
        addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(c).apply {
                background = rounded(0x14ffffff, dp(20f)); dpi(2f).let { setPadding(it, it, it, it) }
                looks.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(36f))) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(40f)))
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            destination.apply {
                val pages = target.notebook.pages; val at = pages.indexOfFirst { it.id == target.pageId } + 1
                text = "Adds to ${target.notebook.title.ifBlank { "this notebook" }} · after page $at"
                textSize = 13f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(0xffbfdbff.toInt()); gravity = Gravity.CENTER_VERTICAL
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; background = rounded(0x380a84ff, dp(18f))
                setPadding(dpi(14f), 0, dpi(14f), 0)
            }
            addView(destination, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(36f)).apply { marginStart = dpi(12f) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, dpi(40f)))
        addView(FrameLayout(c).apply {
            addView(stack, LayoutParams(dpi(120f), dpi(120f), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(shutter, LayoutParams(dpi(96f), dpi(96f), Gravity.CENTER))
            add.apply {
                textSize = 16f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40ffffff),
                    rounded(Glass.ACCENT, dp(28f)), rounded(-1, dp(28f)))
                elevation = dp(6f); outlineSpotShadowColor = Glass.ACCENT; outlineAmbientShadowColor = Glass.ACCENT
                isClickable = true; isFocusable = true; setOnClickListener { add() }
            }
            addView(add, LayoutParams(dpi(200f), dpi(56f), Gravity.END or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dpi(20f) })
    }

    // ---------------------------------------------------------------------------------------------------- Live camera

    private fun startCamera() {
        if (closed || camera != null) return
        cameraError = null; seen = null; hold = 0f; autoCapture.reset()
        // Coming back from an edit, the page last taken may still be in view: Auto does not take it again.
        ScanProcessing.lastTaken?.let { autoCapture.took(it) }
        val cam = ScanCamera(c, viewfinder!!, ::sawPage) { text -> cameraError = text; fail(text) }
        camera = cam
        if (torchOn) cam.setTorch(true)
        cam.open(); show()
    }
    private fun stopCamera() {
        camera?.close(); camera = null
        seen = null; hold = 0f; edges?.points = null
    }
    private fun setTorch(on: Boolean) {
        torchOn = on; camera?.setTorch(on)
        flash?.let { it.isSelected = on; it.stateDescription = if (on) "On" else "Off"; paintRound(it, FLASH, on) }
    }

    /** A frame searched: outline the page, and with Auto on, take it once it has been held still. */
    private fun sawPage(corners: FloatArray?) {
        if (closed) return
        seen = corners
        edges?.points = corners?.let { camera?.toView(it) }
        hold = when {
            // While the pages are being looked through, nothing is taken, and the hold starts again afterwards.
            tray != null -> { autoCapture.restartHold(); 0f }
            auto?.isChecked == true && !working && cameraError == null -> autoCapture.update(corners, SystemClock.uptimeMillis())
            else -> 0f
        }
        if (!autoCapture.waiting) ScanProcessing.lastTaken = null
        if (hold >= 1f) take() else show()
    }

    /** The shutter (or Auto) while live: a full photo, its page found and straightened with the look chosen, then kept. */
    internal fun take() {
        val cam = camera ?: return
        if (working || !cam.started) return
        if (!repository.canInsertImage(target)) return fail(STALE)
        val chosen = look
        autoCapture.took(seen); hold = 0f; ScanProcessing.lastTaken = seen?.copyOf()
        busy(true)
        cam.takePicture { result ->
            result.onFailure { busy(false); if (!closed) fail("Scan failed: ${it.message}") }.onSuccess { keepShot(it, chosen) }
        }
    }
    private fun keepShot(bytes: ByteArray, chosen: ScanProcessing.Look) {
        if (closed) { busy(false); return }
        val file = ScanProcessing.photoFile(app)
        app.io.execute {
            val kept = runCatching {
                file.writeBytes(bytes)
                val photo = ScanProcessing.decode(file)
                try {
                    val corners = ScanProcessing.detect(photo) ?: ScanProcessing.inset(photo)
                    val page = ScanProcessing.straighten(photo, corners, chosen)
                    try { Triple(ScanProcessing.encode(page), small(page), corners) } finally { page.recycle() }
                } finally { photo.recycle() }
            }
            app.main.post {
                busy(false)
                if (closed || ScanProcessing.target == null) { kept.getOrNull()?.second?.recycle(); file.delete(); return@post }
                kept.onSuccess { (page, small, corners) ->
                    ScanProcessing.pages += ScanProcessing.KeptPage(page, small, file, corners, chosen)
                    show(); status.announceForAccessibility("Page $count kept")
                }.onFailure { file.delete(); fail("Scan failed: ${it.message}") }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- Keeping, adding

    /**
     * The shutter, Import from Photos and the row of pages: keep this page as it looks now, close, then go on with [next].
     * A new page joins the end with its photo; a page opened again stays in its place, and is made again only if changed.
     */
    private fun keepThen(next: () -> Unit) {
        if (working) return
        val photo = photo ?: run { activity.closeScan(clear = false); next(); return }
        if (!repository.canInsertImage(target)) return fail(STALE)
        val corners = corners() ?: return fail(CORNERS)
        val chosen = look; val open = review
        if (open != null && open.look == chosen && open.corners == corners) { activity.closeScan(clear = false); next(); return }
        busy(true)
        app.io.execute {
            val kept = runCatching {
                val page = ScanProcessing.straighten(photo, corners, chosen)
                try { ScanProcessing.encode(page) to small(page) } finally { page.recycle() }
            }
            app.main.post {
                busy(false)
                if (closed) { kept.getOrNull()?.second?.recycle(); photo.recycle(); return@post }
                kept.mapCatching { (bytes, small) ->
                    if (open != null) {
                        open.bytes = bytes; open.preview.recycle(); open.preview = small; open.corners = corners; open.look = chosen
                    } else ScanProcessing.pages += ScanProcessing.KeptPage(bytes, small, ScanProcessing.keepPhoto(app, file!!), corners, chosen)
                }.onSuccess { activity.closeScan(clear = false); next() }.onFailure { fail("Scan failed: ${it.message}") }
            }
        }
    }
    private fun removePage(page: ScanProcessing.KeptPage) {
        if (working) return
        activity.closeScan(clear = false)
        ScanProcessing.remove(page)
        if (ScanProcessing.live || ScanProcessing.pages.isNotEmpty()) activity.openScan(null) else activity.captureNextScan()
    }
    private fun small(page: Bitmap): Bitmap {
        val k = PREVIEW_EDGE.toFloat() / max(page.width, page.height)
        return Bitmap.createScaledBitmap(page, max(1, (page.width * k).roundToInt()), max(1, (page.height * k).roundToInt()), true)
            .let { if (it === page) page.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    /** Add: every page kept and this photo, straightened, into the notebook after the open page as one Undo step. */
    private fun add() {
        if (working || count == 0) return
        if (!repository.canInsertImage(target)) return fail(STALE)
        val corners = if (photo != null) corners() ?: return fail(CORNERS) else null
        val chosen = look; val open = review; val kept = ScanProcessing.pages.map { it.bytes }
        val place = ScanProcessing.pages.indexOf(open)
        val unchanged = open != null && open.look == chosen && open.corners == corners
        busy(true)
        app.io.execute {
            val parsed = runCatching {
                val current = if (photo != null && corners != null && !unchanged)
                    ScanProcessing.straighten(photo, corners, chosen).let { page -> try { ScanProcessing.encode(page) } finally { page.recycle() } } else null
                val all = kept.toMutableList().apply { if (current != null) { if (place >= 0) this[place] = current else add(current) } }
                val pages = all.map { ImageSupport.import(ByteArrayInputStream(it), "Scanned page") }
                // Identical pages (the same photo twice in one look) share one stored image.
                Notebook(title = "Scanned pages", pages = pages.flatMap { it.pages }, imageAssets = pages.flatMap { it.imageAssets }.distinctBy { it.id })
            }
            app.main.post {
                busy(false)
                if (closed) { photo?.recycle(); return@post }
                parsed.mapCatching { repository.insertImagePages(target, it) }.onSuccess { ids ->
                    if (ids.isEmpty()) fail(STALE)
                    else {
                        activity.closeScan()
                        if (!activity.isDestroyed) activity.frame.revealPage(ids.first())
                        activity.message(if (ids.size == 1) "Inserted scanned page" else "Inserted ${ids.size} scanned pages")
                    }
                }.onFailure { fail("Scan failed: ${it.message}") }
            }
        }
    }

    private fun corners() = crop?.corners?.map { PointF(it.x, it.y) }?.takeIf { ScanProcessing.convex(it) && ScanProcessing.area(it) >= 64f }
    private fun busy(on: Boolean) { working = on; repository.setFileBusy(on); show() }
    private var failedUntil = 0L
    private fun fail(text: String) {
        failedUntil = SystemClock.uptimeMillis() + 4000
        status.text = text; setMark(Kit.DANGER, null); status.contentDescription = text
        status.announceForAccessibility(text)
    }

    /** Status, button labels and the stack, from the pages so far. */
    private fun show() {
        var ring: Float? = null
        val (text, dot) = when {
            working -> (if (live) "Taking the page…" else "Straightening the page…") to Glass.ACCENT
            live -> when {
                cameraError != null -> cameraError!! to Kit.DANGER
                camera?.started != true -> "Starting the camera…" to Glass.ACCENT
                seen == null -> (if (count == 0) "Point the camera at a page" else "Point the camera at the next page") to Kit.NOTICE
                autoCapture.waiting -> "Page kept — turn to the next page" to Kit.DONE
                auto?.isChecked == true -> { ring = hold; "Page found — hold still" to Glass.ACCENT }
                else -> "Page found — tap the shutter" to Kit.DONE
            }
            photo == null -> (if (count == 1) "1 page ready to add" else "$count pages ready to add") to Glass.ACCENT
            review != null && corners() != null ->
                "Page ${ScanProcessing.pages.indexOf(review) + 1} of ${ScanProcessing.pages.size} — drag a corner or an edge to adjust" to Kit.DONE
            detectedEdges && corners() != null -> "Page edges found — drag a corner or an edge to adjust" to Kit.DONE
            else -> "Drag the corners and edges to the page" to Kit.NOTICE
        }
        // A failure stays readable for a moment before the live status takes over again.
        if (!live || working || SystemClock.uptimeMillis() >= failedUntil) {
            if (status.text.toString() != text) status.text = text
            status.contentDescription = null; setMark(dot, ring)
        }
        add.text = if (count <= 1) "Add page" else "Add $count pages"
        add.contentDescription = if (count <= 1) "Add this page to the notebook" else "Add $count pages to the notebook"
        add.isEnabled = !working && count > 0; add.alpha = if (add.isEnabled) 1f else .4f
        val ready = !working && (!live || camera?.started == true)
        shutter.isEnabled = ready; shutter.alpha = if (ready) 1f else .4f
        whole.isEnabled = photo != null && !working; whole.alpha = if (whole.isEnabled) 1f else .4f
        rotate.isEnabled = whole.isEnabled; rotate.alpha = whole.alpha
        photos.isEnabled = !working; photos.alpha = if (working) .4f else 1f
        remove?.let { it.isEnabled = !working; it.alpha = if (working) .4f else 1f }
        looks.forEach { it.isEnabled = (photo != null || live) && !working; it.alpha = if (it.isEnabled) 1f else .4f }
        stack.contentDescription = (if (count == 1) "1 scan so far" else "$count scans so far") + if (stack.opens) ". Opens the pages to change them" else ""
        stack.isClickable = stack.opens; stack.invalidate()
    }
    /** The status pill's mark: a coloured dot, or the board's ring filling while a page is held still. */
    private fun setMark(color: Int, ring: Float?) {
        mark.color = color; mark.ring = ring
        val size = dpi(if (ring != null) 30f else 10f)
        if (status.compoundDrawablesRelative[0] !== mark || mark.bounds.width() != size) {
            mark.setBounds(0, 0, size, size); status.setCompoundDrawablesRelative(mark, null, null, null)
            status.setPaddingRelative(dpi(if (ring != null) 8f else 16f), 0, dpi(18f), 0)
        }
        mark.invalidateSelf()
    }

    private fun updatePreview() {
        val photo = photo ?: return
        if (closed) return
        val corners = corners() ?: return
        val next = runCatching { ScanProcessing.straighten(photo, corners, look, PREVIEW_EDGE) }.getOrNull() ?: return
        preview?.recycle(); preview = next; stack.invalidate()
        if (resultPane?.visibility != View.VISIBLE) return
        val large = runCatching { ScanProcessing.straighten(photo, corners, look, AFTER_EDGE) }.getOrNull() ?: return
        result?.setImageBitmap(large); result?.contentDescription = "Page after straightening, ${LOOKS.first { it.first == look }.second}"
        after?.recycle(); after = large
    }
    /** A caption over one side of the comparison. */
    private fun pane(caption: String, content: View) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(c).apply {
            text = caption; textSize = 13f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(0xd9ffffff.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, dpi(26f)))
        addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun decodeKept(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun prefs() = c.getSharedPreferences("scan", android.content.Context.MODE_PRIVATE)
    private fun darkGlass(radius: Float) = rounded(0x851c1e24.toInt(), dp(radius), 0x24ffffff, dpi(1f))
    /** A dark glass pill with white words: Cancel and the title. */
    private fun pill(label: String) = TextView(c).apply {
        text = label; textSize = 16f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), darkGlass(25f), rounded(-1, dp(25f)))
        elevation = dp(8f); setPadding(dpi(22f), 0, dpi(22f), 0); isClickable = true; isFocusable = true
    }
    private fun roundButton(label: String, path: String, onClick: () -> Unit) = View(c).apply {
        paintRound(this, path, false)
        contentDescription = label; tooltipText = label; isClickable = true; isFocusable = true
        setOnClickListener { if (isEnabled) runCatching(onClick) }
    }
    /** A white icon on the dark pill, or, [on], a dark icon on a white disc (Flash lit). */
    private fun paintRound(view: View, path: String, on: Boolean) {
        val icon = Kit.Icon(path, if (on) 0xff1d2433.toInt() else Color.WHITE, dpi(22f))
        val layers = if (on) arrayOf(rounded(0xebffffff.toInt(), dp(22f)), icon) else arrayOf<Drawable>(icon)
        view.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),
            android.graphics.drawable.LayerDrawable(layers).apply {
                val i = layers.size - 1; setLayerGravity(i, Gravity.CENTER); setLayerSize(i, dpi(22f), dpi(22f)) }, rounded(-1, dp(22f)))
    }
    private fun chip(name: String, value: ScanProcessing.Look) = TextView(c).apply {
        text = name; textSize = 13.5f; typeface = Kit.Type.HEADLINE.typeface; gravity = Gravity.CENTER; maxLines = 1
        setPadding(dpi(14f), 0, dpi(14f), 0); contentDescription = "$name scan look"; isClickable = true; isFocusable = true
        setOnClickListener {
            if (!isEnabled || working) return@setOnClickListener
            look = value; if (review == null) ScanProcessing.look = value
            looks.forEach { paintChip(it, it === this) }; updatePreview()
        }
        paintChip(this, value == look)
    }
    private fun paintChip(chip: TextView, on: Boolean) {
        chip.isSelected = on
        chip.background = rounded(if (on) 0xebffffff.toInt() else Color.TRANSPARENT, chip.dp(18f))
        chip.setTextColor(if (on) 0xff1d2433.toInt() else 0xd9ffffff.toInt())
    }

    // ---------------------------------------------------------------------------------------------------- The pages

    /** P561: the row of this scan's pages above the panel. Tapping anywhere else closes it. */
    private fun openTray() {
        if (tray != null || working) return
        // The picture of the page open now must not change under the row.
        removeCallbacks(previewLater); updatePreview()
        trayPages.clear()
        val pages = ScanProcessing.pages
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(6f), 0, dpi(6f), 0) }
        pages.forEachIndexed { i, page ->
            row.addView(pageTile(i + 1, if (page === review) preview ?: page.preview else page.preview, page === review) { openPage(page) })
        }
        if (photo != null && review == null) row.addView(pageTile(pages.size + 1, preview, true, null))
        val scroller = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = rounded(0xe61c1e24.toInt(), dp(28f), 0x24ffffff, dpi(1f)); elevation = dp(4f)
            setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(14f))
            addView(TextView(c).apply {
                text = "Tap a page to change its corners"; textSize = 13f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(0xd9ffffff.toInt())
                setPadding(dpi(12f), 0, dpi(12f), dpi(10f))
            })
            addView(scroller)
        }
        val cover = FrameLayout(c).apply {
            // Above the panel, whose elevation would otherwise draw it over this and take its touches.
            elevation = dp(20f); isClickable = true; setOnClickListener { closeTray() }
            addView(card, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dpi(24f), 0, dpi(24f), dpi(276f)) })
        }
        addView(cover, LayoutParams(MATCH_PARENT, MATCH_PARENT)); tray = cover
        // The page open now, or the newest, is in view.
        row.post { row.getChildAt(if (review != null) pages.indexOf(review) else row.childCount - 1)?.let { scroller.scrollTo(it.left - dpi(6f), 0) } }
    }
    private fun closeTray() { tray?.let { removeView(it) }; tray = null; trayPages.clear() }
    private fun pageTile(number: Int, picture: Bitmap?, current: Boolean, open: (() -> Unit)?) = FrameLayout(c).apply {
        background = rounded(Color.WHITE, dp(8f), if (current) Glass.ACCENT else 0x33ffffff, dpi(if (current) 3f else 1f))
        dpi(if (current) 5f else 3f).let { setPadding(it, it, it, it) }
        addView(ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(picture?.takeUnless { it.isRecycled }) }, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(TextView(c).apply {
            text = number.toString(); textSize = 12f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rounded(if (current) Glass.ACCENT else 0xcc1c1e24.toInt(), dp(11f))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dpi(22f), dpi(22f), Gravity.START or Gravity.BOTTOM))
        layoutParams = LinearLayout.LayoutParams(dpi(84f), dpi(112f)).apply { marginStart = dpi(6f); marginEnd = dpi(6f) }
        contentDescription = if (current) "Page $number, open now" else "Page $number. Opens it to change its corners"
        isClickable = true; isFocusable = true
        setOnClickListener { if (open != null) open() else closeTray() }
        trayPages += this
    }
    /** Keeps what is open now (if that fails, it stays open), then opens [page] on its photo. */
    private fun openPage(page: ScanProcessing.KeptPage) {
        closeTray()
        if (page !== review) keepThen { activity.reviewScan(page) }
    }

    /** The pages so far as a little fanned stack, newest in front, with the count on an accent badge. */
    internal inner class Stack : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        /** A tap opens the pages: there is a page kept other than the one open now. */
        val opens get() = ScanProcessing.pages.any { it !== review }
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                if (!opens || working) return@setOnClickListener
                val only = ScanProcessing.pages.singleOrNull()
                // A single page with nothing else open has nothing to choose between.
                if (photo == null && only != null) openPage(only) else openTray()
            }
        }
        override fun onDraw(canvas: Canvas) {
            val papers = (ScanProcessing.pages.map { if (it === review) preview ?: it.preview else it.preview } +
                listOfNotNull(preview?.takeIf { photo != null && review == null })).filterNot { it.isRecycled }.takeLast(3)
            val turns = listOf(-10f, 5f, 0f).takeLast(max(1, papers.size))
            val w = dp(64f); val h = dp(86f)
            turns.forEachIndexed { i, turn ->
                canvas.save(); canvas.rotate(turn, width / 2f, height / 2f)
                rect.set(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2)
                paint.color = Color.WHITE; paint.alpha = if (papers.isEmpty()) 60 else 255; paint.setShadowLayer(dp(4f), 0f, dp(2f), 0x66000000)
                canvas.drawRoundRect(rect, dp(4f), dp(4f), paint); paint.clearShadowLayer(); paint.alpha = 255
                papers.getOrNull(i)?.let { b ->
                    val k = min((w - dp(4f)) / b.width, (h - dp(4f)) / b.height)
                    val bw = b.width * k; val bh = b.height * k
                    canvas.drawBitmap(b, null, RectF(rect.centerX() - bw / 2, rect.centerY() - bh / 2, rect.centerX() + bw / 2, rect.centerY() + bh / 2), paint)
                }
                canvas.restore()
            }
            if (count == 0) return
            if (opens) {
                // Says that the stack can be opened, over the foot of the pages.
                val pw = dp(50f); val ph = dp(24f); val top = height - ph - dp(4f)
                rect.set(width / 2f - pw / 2, top, width / 2f + pw / 2, top + ph)
                paint.color = 0xff2c2f38.toInt(); canvas.drawRoundRect(rect, ph / 2, ph / 2, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = 0xb3ffffff.toInt()
                canvas.drawRoundRect(rect, ph / 2, ph / 2, paint); paint.style = Paint.Style.FILL
                paint.color = Color.WHITE; paint.textSize = dp(13f); paint.typeface = Kit.Type.HEADLINE.typeface; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("Edit", rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
            }
            val r = dp(15f); val cx = width - r - dp(4f); val cy = r + dp(4f)
            paint.color = Color.WHITE; canvas.drawCircle(cx, cy, r + dp(2f), paint)
            paint.color = Glass.ACCENT; canvas.drawCircle(cx, cy, r, paint)
            paint.color = Color.WHITE; paint.textSize = dp(14f); paint.typeface = Kit.Type.HEADLINE.typeface; paint.textAlign = Paint.Align.CENTER
            canvas.drawText(count.toString(), cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }

    /** The white shutter: live, take this page ("Take scan"); on a photo, keep it and scan another; on a page opened again, a tick. */
    internal inner class Shutter : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            contentDescription = if (live) "Take scan" else if (review != null) "Done with this page" else "Keep this page and scan another"
            tooltipText = contentDescription
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                if (live) take() else if (review != null) keepThen { activity.openScan(null) } else keepThen { activity.captureNextScan() }
            }
        }
        override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(4f); paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, min(cx, cy) - dp(2f), paint)
            paint.style = Paint.Style.FILL; paint.color = if (isPressed) 0xffd8dbe2.toInt() else Color.WHITE
            canvas.drawCircle(cx, cy, dp(if (isPressed) 35f else 38f), paint)
            if (review == null) return
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(5f); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = 0xff1d2433.toInt()
            canvas.drawLine(cx - dp(15f), cy + dp(1f), cx - dp(4f), cy + dp(12f), paint); canvas.drawLine(cx - dp(4f), cy + dp(12f), cx + dp(16f), cy - dp(11f), paint)
        }
    }

    /** The page the live camera finds, outlined as on the board: a faint accent fill, accent edges and white corner dots. */
    internal inner class Edges : View(c) {
        /** Corners in this view's pixels, or null to draw nothing. */
        var points: FloatArray? = null
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val p = points ?: return
            path.rewind(); path.moveTo(p[0], p[1]); for (i in 1 until 4) path.lineTo(p[i * 2], p[i * 2 + 1]); path.close()
            paint.style = Paint.Style.FILL; paint.color = 0x240a84ff; canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3.5f); paint.color = Glass.ACCENT; paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
            for (i in 0 until 4) {
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(p[i * 2], p[i * 2 + 1], dp(15f), paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(4f); paint.color = Glass.ACCENT; canvas.drawCircle(p[i * 2], p[i * 2 + 1], dp(15f), paint)
            }
        }
    }

    /** The status pill's dot, or the board's ring: a faint white track with the accent arc filling as the page is held. */
    private inner class StatusMark : Drawable() {
        var color = Glass.ACCENT
        var ring: Float? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds; val cx = b.exactCenterX(); val cy = b.exactCenterY()
            val fill = ring
            if (fill == null) { paint.style = Paint.Style.FILL; paint.color = color; canvas.drawCircle(cx, cy, b.width() / 2f, paint); return }
            val r = b.width() * 12f / 32f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = b.width() * 3.5f / 32f; paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0x33ffffff; canvas.drawCircle(cx, cy, r, paint)
            paint.color = color; canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * fill.coerceIn(0f, 1f), false, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        (background as? GradientDrawable)?.gradientRadius = max(w, h) * .7f
    }

    private companion object {
        const val STALE = "The notebook or page changed. Scan again."
        const val CORNERS = "Move the corners around the page."
        const val PREVIEW_EDGE = 240
        /** The longest edge of the page shown after straightening: sharp at its size without slowing a corner drag. */
        const val AFTER_EDGE = 720
        /** Below this width the photo and its corners keep the whole screen. */
        const val BOTH_WIDTH = 480f
        const val AUTO = "auto"
        /** The board's chips, with the look each one makes. */
        val LOOKS = listOf(ScanProcessing.Look.DOCUMENT to "Colour", ScanProcessing.Look.GREY to "Greyscale",
            ScanProcessing.Look.BLACK_WHITE to "Black & white", ScanProcessing.Look.ORIGINAL to "Photo")
        /** Four corners pulled out to the edges. */
        const val WHOLE = "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"
        const val ROTATE = "M20 12a8 8 0 1 1-2.4-5.7L20 8.5M20 4v4.5h-4.5"
        /** The board's Flash bolt. */
        const val FLASH = "M13 2L4 14h7l-1 8 9-12h-7z"
        const val TRASH = "M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13M10.5 11v5.5M13.5 11v5.5"
    }
}
