package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.ImageAsset
import dev.ownednotes.core.ImageBackground
import dev.ownednotes.core.ImageCrop
import dev.ownednotes.core.ImagePlacement
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.NotebookLimits
import kotlin.math.max
import kotlin.math.min

/**
 * P689: what a picture crop or a picture/page change works on, caught when it started, so a notebook or page that changed
 * meanwhile refuses it. [imageId] is the picture's id, or null for the open page's own photo (an image page).
 */
internal data class PictureTarget(val notebook: Notebook, val pageId: String, val imageId: String?) {
    val page get() = notebook.pages.firstOrNull { it.id == pageId }
    val image get() = imageId?.let { id -> page?.images?.firstOrNull { it.id == id } }
    val isPage get() = imageId == null
    /** The image shown, upright and whole: the picture's own image and turn, or the page's photo (B77: without its crop). */
    val background get() = if (imageId == null) page?.imageBackground?.copy(crop = ImageCrop.FULL) else image?.let { ImageBackground(it.assetId, it.orientation) }
    /** The part shown now: the picture's crop, or (B77) the page photo's. */
    val shownCrop get() = if (imageId == null) page?.imageBackground?.crop else image?.crop
    val asset get() = background?.let { b -> notebook.imageAssets.firstOrNull { it.id == b.assetId } }
    /**
     * P722: the page and the picture in [current] are still as they were caught, whatever else changed meanwhile (ink
     * from the other device through instant sync, another page added there), so a crop still applies to them.
     */
    fun stillIn(current: Notebook?): Boolean {
        if (current == null || current.id != notebook.id) return false
        val was = page ?: return false
        val now = current.pages.firstOrNull { it.id == pageId } ?: return false
        if (now.width != was.width || now.height != was.height || now.imageBackground != was.imageBackground) return false
        if (imageId != null && now.images.firstOrNull { it.id == imageId } != image) return false
        val id = asset?.id ?: return false
        return current.imageAssets.any { it.id == id }
    }
}

internal fun NotebookRepository.capturePictureTarget(imageId: String?): PictureTarget? {
    if (readOnly || !canChangeNotebook || busy || fileBusy) return null
    val note = session?.notebook ?: return null; val page = currentPage ?: return null
    val target = PictureTarget(note, page.id, imageId)
    return target.takeIf { it.background != null && it.asset != null }
}
internal fun NotebookRepository.canChangePicture(target: PictureTarget) = !readOnly && canChangeNotebook && !busy && !fileBusy &&
    selectedPageId == target.pageId && target.stillIn(session?.notebook)
/** P722: the picture is still there as it was, though the notebook may be busy for a moment (saving a change from the other device). */
internal fun NotebookRepository.pictureStillThere(target: PictureTarget) = selectedPageId == target.pageId && target.stillIn(session?.notebook)

/** The open document, as MainActivity's own repository is. */
private val MainActivity.document get() = (application as NotesApplication).activeDocument

/** P689: an image page's size for an image of [width] by [height] pixels, as Image page and Scan size theirs. */
internal fun imagePageSize(width: Int, height: Int): Pair<Float, Float> {
    val scale = PAGE_EDGE / max(width, height)
    require(min(width, height) * scale >= 1f) { "Image aspect ratio is too extreme" }
    return width * scale to height * scale
}
private const val PAGE_EDGE = 1400f
/** The longest edge of a straightened copy: an A4 page at 300 dpi. */
internal const val PICTURE_EDGE = 3508

/**
 * P689: Make page. The selected picture leaves its page and becomes a page of its own right after it, as one Undo step:
 * its own image when it is not cropped, or the cropped part made into a new image.
 */
internal fun MainActivity.makePictureAPage(imageId: String) {
    val target = document.capturePictureTarget(imageId) ?: return
    val image = target.image ?: return
    if (target.notebook.pages.size >= NotebookLimits.PAGES) { message("A notebook can have at most ${NotebookLimits.PAGES} pages"); return }
    val asset = target.asset!!; val background = target.background!!
    fun finish(made: ImageAsset?, shown: ImageBackground, width: Int, height: Int) {
        if (!document.canChangePicture(target)) { message(PICTURE_STALE); return }
        val (w, h) = runCatching { imagePageSize(width, height) }.getOrElse { message(it.message ?: "Cannot make a page"); return }
        var made2: String? = null
        document.edit { made2 = it.pictureToPage(target.pageId, imageId, shown, made, w, h); true }
        val id = made2 ?: return
        document.selectPage(id); frame.revealPage(id)
        message("The picture is now page ${document.session!!.notebook.pages.indexOfFirst { it.id == id } + 1}. Undo puts it back.")
    }
    if (image.crop == ImageCrop.FULL) {
        val sideways = image.orientation >= 5
        finish(null, background, if (sideways) asset.height else asset.width, if (sideways) asset.width else asset.height)
        return
    }
    // A cropped picture: its visible part becomes the page's image, so the page shows what the picture showed.
    val app = application as NotesApplication
    document.setFileBusy(true)
    PictureCropScreen.worker.execute {
        val result = runCatching {
            val full = ImageSupport.render(asset, background, 4096)
            try {
                val crop = image.crop
                val l = (crop.left * full.width).toInt(); val t = (crop.top * full.height).toInt()
                val w = max(1, (crop.width * full.width).toInt().coerceAtMost(full.width - l)); val h = max(1, (crop.height * full.height).toInt().coerceAtMost(full.height - t))
                val part = Bitmap.createBitmap(full, l, t, w, h)
                try { Triple(ImageAsset.fromBytes(ScanProcessing.encode(part)), part.width, part.height) } finally { if (part !== full) part.recycle() }
            } finally { full.recycle() }
        }
        app.main.post {
            document.setFileBusy(false)
            if (isDestroyed) return@post
            result.onSuccess { (made, w, h) -> finish(made, ImageBackground(made.id), w, h) }.onFailure { message("Cannot make a page: ${it.message}") }
        }
    }
}

/** P689: Make picture. The open image page's photo becomes a picture covering the page, selected, as one Undo step. */
internal fun MainActivity.makePageAPicture() {
    val target = document.capturePictureTarget(null) ?: return
    if (!target.isPage) return
    var id: String? = null
    document.edit { id = it.pageToPicture(target.pageId); true }
    id?.let { ink.selectPhoto(it); message("The page's image is now a picture. Undo makes it the page again.") }
}

/**
 * P720: opens the crop screen by itself on a picture just imported ([imageId]), or with null on the image page just made,
 * once the import is saved, while nothing else was done here since the import ([mark]; P722: changes from the other device
 * do not count). It gives up quietly when something was, or after 20 s; Crop in the picture's bar and Crop page… still
 * open it.
 */
internal fun MainActivity.cropImported(imageId: String?, mark: Long, tries: Int = 0) {
    // P722: [mark] is the notebook's step count right after the import; changes from the other device do not move it.
    if (isDestroyed || pictureCrop != null || document.session?.localSteps != mark) return
    if (document.capturePictureTarget(imageId) != null) { openPictureCrop(imageId, importMark = mark); return }
    if (tries < 200) frame.root.postDelayed({ cropImported(imageId, mark, tries + 1) }, 100)
}

/** P689: opens the crop screen on the selected picture ([imageId]) or, with null, on the open image page's photo. */
internal fun MainActivity.openPictureCrop(imageId: String?, importMark: Long? = null) {
    if (pictureCrop != null || ink.isEditing) return
    val target = document.capturePictureTarget(imageId) ?: return
    val app = application as NotesApplication
    document.setFileBusy(true)
    PictureCropScreen.worker.execute {
        val preview = runCatching { ImageSupport.render(target.asset!!, target.background!!, PictureCropScreen.PREVIEW_EDGE) }
        app.main.post {
            document.setFileBusy(false)
            if (isDestroyed || pictureCrop != null) { preview.getOrNull()?.recycle(); return@post }
            preview.onSuccess { photo ->
                if (!document.canChangePicture(target)) { photo.recycle(); message(PICTURE_STALE); return@post }
                showPictureCrop(PictureCropScreen(this, document, target, photo, importMark))
            }.onFailure { message("Cannot open the picture: ${it.message}") }
        }
    }
}
internal const val PICTURE_STALE = "The notebook or page changed. Open Crop again."

/**
 * P689, redesigned in P720: Crop picture / Crop page, a dark full screen like a photo editor's. The top bar holds Cancel
 * (a cross), the title and Done. The picture fills the middle in its own colours. A panel at the foot has three tools,
 * chosen by the tabs under it:
 * - **Crop:** Straight (four sides square to the picture: a corner drags two sides, a side moves across) or Corners (four
 *   free corners that straighten a page photographed at an angle, as Scan does), then Find page, Whole and, for pictures,
 *   Exact numbers…;
 * - **Straighten:** a dial that turns the picture up to 45 degrees either way with a grid over it, Turn 90°, Auto (levels
 *   the page found in the photo) and Reset;
 * - **Look:** the picture as it will come out in each of the scan looks (Photo, Colour, Greyscale, Black & white), side by
 *   side. On a phone the middle then shows the result itself; touch and hold it to see it without the look.
 * With room (a tablet), the result also shows beside the picture all along.
 *
 * Done on a picture with a straight, level crop in the Photo look and no turn keeps the original image and only frames it
 * (a crop that can be changed back any time); anything else makes a straightened copy that takes the original's place,
 * and Undo brings the original back. On an image page the page takes the copy's shape, keeping its width.
 *
 * [imported]: opened by itself straight after the picture was imported (as an image page or onto the page). Done then
 * reads Add page or Add picture, and a crop joins the import as one Undo step; the cross adds nothing; Back keeps it as
 * imported. While importing, a page found in the photo is offered as "Page found", never applied by itself.
 */
internal class PictureCropScreen(
    private val activity: MainActivity,
    private val repository: NotebookRepository,
    internal val target: PictureTarget,
    private val photo: Bitmap,
    /** P720, P722: opened by itself right after an import: the notebook's step count just after it, else null. */
    internal val importMark: Long? = null,
) : FrameLayout(activity) {
    internal val imported get() = importMark != null
    internal enum class Tool(val label: String, val icon: String) { CROP("Crop", CROP_ICON), STRAIGHTEN("Straighten", LEVEL), LOOK("Look", LOOKS_ICON) }
    private val c = activity
    private val app = activity.application as NotesApplication
    private val isPage = target.isPage
    /** Where the corners start: the picture's or (B77) the page's crop; uncropped, the whole photo. */
    private val start: List<PointF> = target.shownCrop?.let { k ->
        listOf(PointF(k.left * photo.width, k.top * photo.height), PointF(k.right * photo.width, k.top * photo.height),
            PointF(k.right * photo.width, k.bottom * photo.height), PointF(k.left * photo.width, k.bottom * photo.height))
    } ?: ScanProcessing.whole(photo)
    internal val crop: ScanCropView = ScanCropView(c, photo, start).apply {
        contentDescription = if (isPage) "Page corners and edges" else "Picture corners and edges"; tint = false
    }
    internal var look = ScanProcessing.Look.ORIGINAL; private set
    internal var tool = Tool.CROP; private set
    private val names = if (imported) (if (isPage) "New image page" to "Add page" else "New picture" to "Add picture")
        else (if (isPage) "Crop page" to "Done" else "Crop picture" to "Done")

    // The top bar.
    internal val cancel = roundButton(if (imported) "Don't add" else "Cancel", CLOSE) { cancel() }
    internal val title = TextView(c)
    internal val done = TextView(c)
    // Crop.
    internal val straightMode = mode("Straight", "Four sides, square to the picture", STRAIGHT, true)
    internal val cornersMode = mode("Corners", "Four corners, straightens a page taken at an angle", CORNERS, false)
    internal val find = action("Find page", FIND, "Find the page in it") { findPage() }
    internal val whole = action("Whole", WHOLE, if (isPage) "Whole page" else "Whole picture") {
        crop.setCorners(ScanProcessing.wholeLike(photo, crop.corners))
    }
    /** Pictures only: the earlier crop with exact numbers. */
    internal val exact: TextView? = if (isPage) null else action("Exact numbers…", NUMBERS, "Crop with exact numbers") {
        if (look == ScanProcessing.Look.ORIGINAL && crop.angle == 0f) {
            val id = target.imageId!!; activity.closePictureCrop(); activity.photoCropDialog(id) { activity.ink.selectPhoto(id) }
        }
    }
    // Straighten.
    internal val dial = AngleDial(c)
    internal val turn = action("Turn 90°", ROTATE, "Turn a quarter") { crop.turnClockwise(); note.announceForAccessibility("Turned") }
    internal val level = action("Auto", AUTO, "Straighten by itself") { autoStraighten() }
    internal val reset = action("Reset", RESET, "Straighten back to level") { straighten(0f, angleBase); dial.value = 0f }
    // Look.
    internal val looks: List<TextView> = LOOKS.map { (value, name) -> lookTile(name, value) }
    private val thumbs = looks.map { it.compoundDrawablesRelative[1] as LookThumb }
    private val thumbBitmaps = arrayOfNulls<Bitmap>(LOOKS.size)
    // The middle.
    internal val result = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    internal val resultPane = pane("After", result)
    private val cropPane = pane("Before", crop)
    /** While importing: the page found in the photo, offered and never applied by itself. */
    internal val found = TextView(c)
    internal val note = TextView(c)
    internal val tabs: List<TextView> = Tool.values().map { tab(it) }
    private val tools = mapOf(Tool.CROP to cropTool(), Tool.STRAIGHTEN to straightenTool(), Tool.LOOK to lookTool())
    private val column = LinearLayout(c)
    private val panel = LinearLayout(c)

    private var after: Bitmap? = null
    /** The result in the Photo look, kept while another look is chosen so touching and holding the result shows it. */
    private var afterPlain: Bitmap? = null
    private var holding = false
    private var working = false
    /** B74: preview, thumbnail and Find jobs still reading [photo] on [worker]; the photo is recycled only once none is left. */
    private var jobs = 0
    @Volatile private var previewGeneration = 0
    @Volatile private var thumbGeneration = 0
    private var finding = false
    private var closed = false
    private var failed = false
    private var foundCorners: List<PointF>? = null
    /** The outline before the dial last moved, so turning back to level gives it back. */
    private var angleBase: List<PointF> = start.map { PointF(it.x, it.y) }
    private val previewLater = Runnable { updatePreview(); updateThumbs() }
    private val narrow = resources.configuration.screenWidthDp < 600
    /** Room for the result beside the picture. */
    private var wide = false

    init {
        // Darkest at the top and foot, where it meets the system bars in the same colour; a little lighter behind the picture.
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BACK, 0xff22252c.toInt(), BACK))
        isClickable = true
        title.apply {
            text = names.first; textSize = 17f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; isAccessibilityHeading = true
        }
        accessibilityPaneTitle = names.first
        crop.straight = prefs().getBoolean(STRAIGHT_KEY, true)
        crop.onCornersChanged = { failed = false; show(); removeCallbacks(previewLater); postDelayed(previewLater, 120) }
        dial.onHold = { held -> crop.grid = held || tool == Tool.STRAIGHTEN; if (held) angleBase = crop.corners.map { PointF(it.x, it.y) } }
        dial.onChange = { value, _ -> straighten(value, angleBase) }
        done.apply {
            text = names.second; textSize = 15.5f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
            setPadding(dpi(20f), 0, dpi(20f), 0)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40ffffff),
                rounded(Glass.ACCENT, dp(22f)), rounded(-1, dp(22f)))
            isClickable = true; isFocusable = true; setOnClickListener { done() }
        }
        found.apply {
            text = "Page found · Fit to it"; textSize = 14f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            compoundDrawablePadding = dpi(8f); setPadding(dpi(14f), 0, dpi(18f), 0); elevation = dp(6f)
            setCompoundDrawablesRelative(Kit.Icon(FIND, Color.WHITE, dpi(20f)).apply { setBounds(0, 0, dpi(20f), dpi(20f)) }, null, null, null)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40ffffff),
                rounded(0xf20a66d9.toInt(), dp(20f)), rounded(-1, dp(20f)))
            contentDescription = "A page was found in the photo. Fit the crop to it"; isClickable = true; isFocusable = true
            visibility = View.GONE; setOnClickListener { foundCorners?.let { fitToPage(it) }; visibility = View.GONE }
        }
        note.apply {
            textSize = 12.5f; typeface = Kit.Type.CAPTION.typeface; setTextColor(NOTE); gravity = Gravity.CENTER; maxLines = 2
            ellipsize = TextUtils.TruncateAt.END; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        result.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { val plain = afterPlain; if (plain != null && !plain.isRecycled) { holding = true; result.setImageBitmap(plain); note.text = "Without the look" } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (holding) { holding = false; result.setImageBitmap(after); show() }
            }
            true
        }

        column.orientation = LinearLayout.VERTICAL
        column.addView(FrameLayout(c).apply {
            addView(cancel, LayoutParams(dpi(44f), dpi(44f), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(title, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER).apply { setMargins(dpi(64f), 0, dpi(64f), 0) })
            addView(done, LayoutParams(WRAP_CONTENT, dpi(44f), Gravity.END or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dpi(60f)).apply { setMargins(dpi(12f), dpi(4f), dpi(12f), 0) })
        column.addView(FrameLayout(c).apply {
            addView(LinearLayout(c).apply {
                addView(cropPane, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(resultPane, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dpi(16f) })
                addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) v.post { wide = r - l >= dpi(480f); arrange() } }
            }, LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(dpi(if (narrow) 4f else 20f), 0, dpi(if (narrow) 4f else 20f), 0) })
            addView(found, LayoutParams(WRAP_CONTENT, dpi(40f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(8f) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        column.addView(note, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dpi(24f), dpi(6f), dpi(24f), dpi(8f)) })
        panel.apply {
            orientation = LinearLayout.VERTICAL; background = rounded(0xe61f2228.toInt(), dp(28f), 0x1fffffff, dpi(1f)); elevation = dp(12f)
            setPadding(dpi(10f), dpi(12f), dpi(10f), dpi(6f))
            addView(FrameLayout(c).apply { tools.values.forEach { addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT)) } },
                LinearLayout.LayoutParams(MATCH_PARENT, dpi(TOOL_HEIGHT)))
            addView(View(c).apply { setBackgroundColor(0x1affffff) }, LinearLayout.LayoutParams(MATCH_PARENT, dpi(1f)).apply { setMargins(dpi(8f), dpi(8f), dpi(8f), dpi(2f)) })
            addView(LinearLayout(c).apply { tabs.forEach { addView(it, LinearLayout.LayoutParams(0, dpi(58f), 1f)) } }, LinearLayout.LayoutParams(MATCH_PARENT, dpi(58f)))
        }
        column.addView(FrameLayout(c).apply {
            addView(panel, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
                if (r - l != or_ - ol) v.post { (panel.layoutParams as LayoutParams).width = kotlin.math.min(r - l, dpi(PANEL_MAX)); panel.requestLayout() }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dpi(10f), 0, dpi(10f), dpi(10f)) })
        addView(column, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        choose(Tool.CROP); paintModes(); show()
        if (imported) lookForPage()
    }

    // ---- Layout of the tools.
    private fun cropTool() = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(LinearLayout(c).apply {
            background = rounded(0x14ffffff, dp(22f)); dpi(2f).let { setPadding(it, it, it, it) }
            addView(straightMode, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(40f)))
            addView(cornersMode, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(40f)))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(44f)))
        addView(row(listOfNotNull(find, whole, exact)), LinearLayout.LayoutParams(MATCH_PARENT, dpi(44f)).apply { topMargin = dpi(12f) })
    }
    private fun straightenTool() = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(dial, LinearLayout.LayoutParams(MATCH_PARENT, dpi(68f)))
        addView(row(listOf(turn, level, reset)), LinearLayout.LayoutParams(MATCH_PARENT, dpi(44f)).apply { topMargin = dpi(4f) })
    }
    private fun lookTool() = LinearLayout(c).apply {
        gravity = Gravity.CENTER
        looks.forEach { addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
    }
    /** Action chips in a centred row that scrolls sideways only when the screen is too narrow for them. */
    private fun row(items: List<View>) = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true
        addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER
            items.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(40f)).apply { if (i > 0) marginStart = dpi(8f) }) }
        }, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }

    /** Shows [chosen]'s tool, and on a narrow screen the result in place of the picture while choosing a look. */
    internal fun choose(chosen: Tool) {
        tool = chosen
        tools.forEach { (t, v) -> v.visibility = if (t == chosen) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, tab -> paintTab(tab, Tool.values()[i] == chosen) }
        crop.grid = chosen == Tool.STRAIGHTEN
        arrange(); show()
    }
    private fun arrange() {
        val showResult = wide || tool == Tool.LOOK
        cropPane.visibility = if (wide || tool != Tool.LOOK) View.VISIBLE else View.GONE
        resultPane.visibility = if (showResult) View.VISIBLE else View.GONE
        // Captions only where both show.
        listOf(cropPane, resultPane).forEach { (it.getChildAt(0) as TextView).visibility = if (wide) View.VISIBLE else View.GONE }
        (resultPane.layoutParams as LinearLayout.LayoutParams).marginStart = if (wide) dpi(16f) else 0
        if (showResult) updatePreview()
        if (tool == Tool.LOOK) updateThumbs()
    }

    /** Leaves without changing anything; just imported, the picture goes again (nothing is added). */
    fun cancel() {
        if (working) return
        if (imported) {
            // P722: saving a change from the other device makes the notebook busy for a moment: try again shortly.
            if ((repository.busy || repository.readOnly) && waits < WAITS) { waits++; postDelayed({ if (!closed) cancel() }, WAIT_MS); return }
            var taken = false
            repository.edit { taken = it.discardLastStep(importMark!!); taken }
            activity.closePictureCrop()
            activity.message(if (taken) (if (isPage) "No page added" else "No picture added") else if (isPage) "The page stays. Undo takes it out." else "The picture stays. Undo takes it out.")
            return
        }
        leave()
    }
    /** Back: as Cancel, except that a picture just imported stays as it came in. */
    fun back() { if (working) return; if (imported) { leave(); activity.message(addedText()) } else cancel() }
    private fun addedText() = if (isPage) "Image page added. Undo takes it out." else "Picture added. Undo takes it out."
    /** Closes the screen, the picture selected again. */
    private fun leave() { activity.closePictureCrop(); target.imageId?.let { activity.ink.selectPhoto(it) } }
    /** Called once the screen has left the window. */
    fun dispose() {
        closed = true; removeCallbacks(previewLater); result.setImageDrawable(null); thumbs.forEach { it.bitmap = null }
        after?.recycle(); after = null; afterPlain?.recycle(); afterPlain = null
        thumbBitmaps.forEachIndexed { i, b -> b?.recycle(); thumbBitmaps[i] = null }
        release()
    }
    /** Recycles the photo once the screen is gone and no background job reads it any more. */
    private fun release() { if (closed && !working && jobs == 0 && !photo.isRecycled) photo.recycle() }

    /**
     * A straight, level crop of the unturned picture in its own colours only frames it; anything else makes a new image.
     * B77 (schema 39): the same goes for an image page, whose photo keeps its original and stores the crop.
     */
    internal val keepsOriginal get() = crop.straight && crop.angle == 0f && look == ScanProcessing.Look.ORIGINAL && upright()
    private fun upright() = crop.corners[0].let { p -> p.x <= crop.corners.minOf { it.x } + .5f && p.y <= crop.corners.minOf { it.y } + .5f }
    private fun unchanged() = look == ScanProcessing.Look.ORIGINAL && crop.angle == 0f && crop.corners.indices.all { i ->
        kotlin.math.abs(crop.corners[i].x - start[i].x) < .5f && kotlin.math.abs(crop.corners[i].y - start[i].y) < .5f }
    /** The outline in the photo itself (turned back when straightened), or null when it is too small or folded. */
    private fun corners() = crop.photoCorners().takeIf { ScanProcessing.convex(it) && ScanProcessing.area(it) >= 64f }
    /** Runs [change] as one edit; just imported, it joins the import's Undo step. */
    private fun commit(change: (dev.ownednotes.core.DocumentSession) -> Boolean) = repository.edit { s ->
        change(s).also { changed -> if (changed && imported) s.joinWithPrevious(importMark!!) }
    }

    internal fun done() {
        if (working) return
        if (unchanged()) { leave(); if (imported) activity.message(addedText()); return }
        val corners = corners() ?: return fail("Move the corners apart.")
        if (!repository.canChangePicture(target)) return later { done() }
        waits = 0
        if (keepsOriginal) {
            val left = corners.minOf { it.x } / photo.width; val right = corners.maxOf { it.x } / photo.width
            val top = corners.minOf { it.y } / photo.height; val bottom = corners.maxOf { it.y } / photo.height
            val framed = runCatching { ImageCrop(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)) }
                .getOrElse { return fail("Move the corners apart.") }
            if (isPage) {
                val page = target.page!!; val asset = target.asset!!; val sideways = target.background!!.orientation >= 5
                val uprightWidth = if (sideways) asset.height else asset.width; val uprightHeight = if (sideways) asset.width else asset.height
                val h = (page.width * framed.height * uprightHeight / (framed.width * uprightWidth)).coerceIn(1f, 100_000f)
                commit { it.cropPageImage(target.pageId, framed, page.width, h) }
                activity.closePictureCrop(); activity.message(if (imported) "Image page added, cropped. Undo takes it out." else "Page cropped. Undo brings back the photo as it was.")
                return
            }
            val id = target.imageId!!
            commit { it.cropImageObject(target.pageId, id, framed) }
            activity.closePictureCrop(); activity.ink.selectPhoto(id)
            activity.message(if (imported) "Picture added, cropped. Undo takes it out." else "Picture cropped. Undo brings back the whole picture.")
            return
        }
        val chosen = look; val asset = target.asset!!; val background = target.background!!
        working = true; repository.setFileBusy(true); show()
        PictureCropScreen.worker.execute {
            val made = runCatching {
                // B76: the full image goes before the look is applied, so a phone has room for the full-size look.
                val full = ImageSupport.render(asset, background, 4096)
                val out = try {
                    val kx = full.width.toFloat() / photo.width; val ky = full.height.toFloat() / photo.height
                    ScanProcessing.straighten(full, corners.map { PointF(it.x * kx, it.y * ky) }, ScanProcessing.Look.ORIGINAL, PICTURE_EDGE)
                } finally { full.recycle() }
                try { ScanProcessing.enhance(out, chosen); Triple(ImageAsset.fromBytes(ScanProcessing.encode(out)), out.width, out.height) } finally { out.recycle() }
            }
            app.main.post {
                working = false; repository.setFileBusy(false)
                if (closed) { release(); return@post }
                made.onSuccess { (image, w, h) -> place(image, w, h) }.onFailure { fail("Cannot crop: ${it.message}") }
            }
        }
    }
    /** Puts the straightened copy in the original's place. */
    private fun place(image: ImageAsset, width: Int, height: Int) {
        if (!repository.canChangePicture(target)) return later { place(image, width, height) }
        val outcome = runCatching {
            if (isPage) {
                val page = target.page!!
                val h = (page.width * height / width).coerceIn(1f, 100_000f)
                commit { it.replacePageImage(target.pageId, image, page.width, h) }
                if (imported) "Image page added. Undo takes it out." else "Page cropped. Undo brings back the photo as it was."
            } else {
                val picture = target.image!!
                // Where the chosen part shows now (the outline as it shows on screen), with the copy fitted inside it
                // around the same centre.
                val shown = crop.corners
                val k = ImageCrop((shown.minOf { it.x } / photo.width).coerceIn(0f, 1f), (shown.minOf { it.y } / photo.height).coerceIn(0f, 1f),
                    (shown.maxOf { it.x } / photo.width).coerceIn(0f, 1f), (shown.maxOf { it.y } / photo.height).coerceIn(0f, 1f))
                val box = k.reframe(picture.placement, picture.crop)
                val aspect = width.toFloat() / height
                val (w, h) = if (aspect > box.width / box.height) box.width to box.width / aspect else box.height * aspect to box.height
                val placement = ImagePlacement(box.x + (box.width - w) / 2, box.y + (box.height - h) / 2, max(1f, w), max(1f, h), box.degrees)
                commit { it.replaceImageObjectAsset(target.pageId, picture.id, image, placement) }
                if (imported) "Picture added. Undo takes it out." else "Picture cropped. Undo brings back the original."
            }
        }
        outcome.onSuccess { text ->
            val id = target.imageId
            activity.closePictureCrop()
            if (id != null) activity.ink.selectPhoto(id)
            activity.message(text)
        }.onFailure { fail("Cannot crop: ${it.message}") }
    }

    // ---- Straighten.
    private fun straighten(degrees: Float, base: List<PointF>) {
        if (working) return
        crop.setAngle(degrees, base); failed = false; show()
    }
    /** Levels the page found in the photo: its top and bottom edges become level. */
    private fun autoStraighten() {
        if (finding) return
        detect { corners ->
            if (corners == null) { fail("No page edge found. Turn the dial instead."); return@detect }
            fun slope(a: PointF, b: PointF) = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            // The page's corners in the photo as seen: the sides nearest level are its top and bottom.
            val tilt = listOf(slope(corners[0], corners[1]), slope(corners[3], corners[2])).average().toFloat()
            val turnBy = (-tilt).let { if (it > 45f) it - 90f else if (it < -45f) it + 90f else it }
            if (kotlin.math.abs(turnBy) < .1f) { note.text = "Already level."; return@detect }
            angleBase = crop.corners.map { PointF(it.x, it.y) }
            straighten(turnBy, angleBase); dial.value = crop.angle
            note.announceForAccessibility("Straightened by ${AngleDial.label(crop.angle)}")
        }
    }

    // ---- Finding the page.
    /** B74: finding the page runs on [worker]; the screen keeps answering meanwhile. [then] gets the corners in the photo, or null. */
    private fun detect(then: (List<PointF>?) -> Unit) {
        foundCorners?.let { return then(it) }
        finding = true; jobs++; note.setTextColor(NOTE); note.text = "Looking for the page…"
        PictureCropScreen.worker.execute {
            val found = runCatching { ScanProcessing.detect(photo) }.getOrNull()
            app.main.post {
                jobs--; finding = false
                if (closed) { release(); return@post }
                foundCorners = found; show(); then(found)
            }
        }
    }
    private fun findPage() {
        if (finding) return
        detect { found -> if (found == null) fail("No page found. Drag the corners to it.") else fitToPage(found) }
    }
    /** Corners on the page found: level again, in Corners mode. */
    private fun fitToPage(corners: List<PointF>) {
        found.visibility = View.GONE
        if (crop.angle != 0f) { crop.setAngle(0f, corners); dial.value = 0f }
        crop.straight = false; prefs().edit().putBoolean(STRAIGHT_KEY, false).apply(); paintModes()
        crop.setCorners(corners); note.announceForAccessibility("Fitted to the page")
    }
    /** While importing: a page clearly smaller than the photo is offered with "Page found". */
    private fun lookForPage() {
        detect { corners ->
            if (corners != null && ScanProcessing.area(corners) < photo.width * photo.height * .9f && unchanged()) found.visibility = View.VISIBLE
        }
    }

    /**
     * P722: the notebook is busy for a moment (saving, or taking a change from the other device) while the picture is still
     * as it was: [again] runs shortly. Only a picture that really changed is refused.
     */
    private fun later(again: () -> Unit) {
        if (!repository.pictureStillThere(target) || waits >= WAITS) { waits = 0; return fail(PICTURE_STALE) }
        waits++; note.setTextColor(NOTE); note.text = "One moment…"
        postDelayed({ if (!closed) again() }, WAIT_MS)
    }
    private var waits = 0
    private fun fail(text: String) {
        failed = true; note.text = text; note.setTextColor(FAULT); note.announceForAccessibility(text)
    }
    private fun show() {
        if (!failed && !finding && !holding) {
            note.setTextColor(NOTE)
            note.text = when {
                working -> "Straightening…"
                tool == Tool.LOOK && look != ScanProcessing.Look.ORIGINAL && !wide -> "Touch and hold to see it without the look."
                tool == Tool.STRAIGHTEN && crop.angle == 0f -> "Drag the dial until the lines are level."
                unchanged() -> when { imported -> "Crop, straighten or choose a look, or add it as it is."; crop.straight -> "Drag a side or a corner to crop."; else -> "Drag each corner to a corner of the page." }
                keepsOriginal -> if (imported) "Adds the whole picture, cropped. Crop can bring the rest back." else "Crops the picture. The rest stays, so Crop can bring it back."
                isPage -> if (imported) "Adds a straightened page image in this shape." else "Makes a new page image in this shape. Undo brings back the photo."
                else -> if (imported) "Adds a straightened copy." else "Makes a straightened copy in its place. Undo brings back the original."
            }
        }
        done.isEnabled = !working; done.alpha = if (working) .4f else 1f
        listOfNotNull(cancel, turn, whole, find, level, reset, straightMode, cornersMode, dial).forEach { it.isEnabled = !working; it.alpha = if (working) .4f else 1f }
        // B76: the exact-numbers crop has no looks or straightening, so it would drop them; it is offered for a plain crop only.
        exact?.let { val on = !working && look == ScanProcessing.Look.ORIGINAL && crop.angle == 0f; it.isEnabled = on; it.alpha = if (on) 1f else .4f
            it.contentDescription = if (on) "Crop with exact numbers" else "Crop with exact numbers, Photo look and level only" }
        reset.isEnabled = !working && crop.angle != 0f; reset.alpha = if (reset.isEnabled) 1f else .4f
        looks.forEach { it.isEnabled = !working; it.alpha = if (working) .4f else 1f }
    }
    /** B74: the result is made on [worker]; only the newest request is shown, older ones are skipped. */
    private fun updatePreview() {
        if (closed || resultPane.visibility != View.VISIBLE) return
        val corners = corners() ?: return
        val chosen = look; val generation = ++previewGeneration
        val edge = if (wide) WIDE_EDGE else PHONE_EDGE
        jobs++
        PictureCropScreen.worker.execute {
            var plain: Bitmap? = null; var shown: Bitmap? = null
            if (generation == previewGeneration) runCatching {
                plain = ScanProcessing.straighten(photo, corners, ScanProcessing.Look.ORIGINAL, edge)
                shown = if (chosen == ScanProcessing.Look.ORIGINAL) plain else plain!!.copy(Bitmap.Config.ARGB_8888, true).also { ScanProcessing.enhance(it, chosen) }
            }.onFailure { if (shown !== plain) shown?.recycle(); plain?.recycle(); plain = null; shown = null }
            app.main.post {
                jobs--
                val made = shown
                if (closed || generation != previewGeneration || made == null) { if (shown !== plain) shown?.recycle(); plain?.recycle(); release(); return@post }
                result.setImageBitmap(made); result.contentDescription = "Result, ${LOOKS.first { it.first == chosen }.second}" +
                    if (chosen != ScanProcessing.Look.ORIGINAL) ". Touch and hold to see it without the look." else ""
                holding = false
                val old = after; val oldPlain = afterPlain
                after = made; afterPlain = if (plain !== made) plain else null
                if (old !== made) old?.recycle(); if (oldPlain !== made && oldPlain !== afterPlain) oldPlain?.recycle()
            }
        }
    }
    /** The small previews of each look, made from the outline as it is now; only while the Look tool shows. */
    private fun updateThumbs() {
        if (closed || tool != Tool.LOOK) return
        val corners = corners() ?: return
        val generation = ++thumbGeneration
        jobs++
        PictureCropScreen.worker.execute {
            val made = if (generation != thumbGeneration) null else runCatching {
                val small = ScanProcessing.straighten(photo, corners, ScanProcessing.Look.ORIGINAL, THUMB_EDGE)
                LOOKS.map { (value, _) ->
                    if (value == ScanProcessing.Look.ORIGINAL) small else small.copy(Bitmap.Config.ARGB_8888, true).also { ScanProcessing.enhance(it, value) }
                }
            }.getOrNull()
            app.main.post {
                jobs--
                if (closed || generation != thumbGeneration || made == null) { made?.forEach { it.recycle() }; release(); return@post }
                made.forEachIndexed { i, b -> val old = thumbBitmaps[i]; thumbs[i].bitmap = b; thumbBitmaps[i] = b; if (old !== b) old?.recycle() }
            }
        }
    }

    private fun prefs() = c.getSharedPreferences("picture-crop", android.content.Context.MODE_PRIVATE)
    private fun roundButton(label: String, path: String, onClick: () -> Unit) = View(c).apply {
        val icon = Kit.Icon(path, Color.WHITE, dpi(22f))
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),
            android.graphics.drawable.LayerDrawable(arrayOf<Drawable>(rounded(0x1fffffff, dp(22f)), icon)).apply { setLayerGravity(1, Gravity.CENTER); setLayerSize(1, dpi(22f), dpi(22f)) },
            rounded(-1, dp(22f)))
        contentDescription = label; tooltipText = label; isClickable = true; isFocusable = true
        setOnClickListener { if (isEnabled && !working) runCatching(onClick) }
    }
    /** An action chip: its drawing and a short name; [said] is its full name for TalkBack. */
    private fun action(name: String, path: String, said: String, onClick: () -> Unit) = TextView(c).apply {
        text = name; textSize = 13.5f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(0xebffffff.toInt()); gravity = Gravity.CENTER; maxLines = 1
        compoundDrawablePadding = dpi(7f); setPadding(dpi(13f), 0, dpi(15f), 0)
        setCompoundDrawablesRelative(Kit.Icon(path, 0xebffffff.toInt(), dpi(18f)).apply { setBounds(0, 0, dpi(18f), dpi(18f)) }, null, null, null)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), rounded(0x1affffff, dp(20f)), rounded(-1, dp(20f)))
        contentDescription = said; tooltipText = said; isClickable = true; isFocusable = true
        setOnClickListener { if (isEnabled && !working) runCatching(onClick) }
    }
    private fun pane(caption: String, content: View) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(c).apply {
            text = caption; textSize = 13f; typeface = Kit.Type.HEADLINE.typeface; setTextColor(0xb3ffffff.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, dpi(26f)))
        addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }
    private fun tab(t: Tool) = TextView(c).apply {
        text = t.label; textSize = 12f; typeface = Kit.Type.HEADLINE.typeface; gravity = Gravity.CENTER; maxLines = 1
        compoundDrawablePadding = dpi(3f); setPadding(0, dpi(6f), 0, dpi(4f))
        tag = t.icon; contentDescription = t.label; isClickable = true; isFocusable = true
        setOnClickListener { if (!working && tool != t) choose(t) }
    }
    private fun paintTab(tab: TextView, on: Boolean) {
        tab.isSelected = on
        val colour = if (on) Color.WHITE else 0x99ffffff.toInt()
        tab.setTextColor(colour)
        tab.setCompoundDrawablesRelative(null, Kit.Icon(tab.tag as String, if (on) Glass.ACCENT_NIGHT else colour, dpi(22f)).apply { setBounds(0, 0, dpi(22f), dpi(22f)) }, null, null)
        tab.background = if (on) rounded(0x1affffff, dp(18f)) else null
    }
    /** A mode chip: its drawing (a square frame, or a slanted one) and its name. */
    private fun mode(name: String, hint: String, path: String, straight: Boolean) = TextView(c).apply {
        text = name; textSize = 14f; typeface = Kit.Type.HEADLINE.typeface; gravity = Gravity.CENTER_VERTICAL; maxLines = 1
        compoundDrawablePadding = dpi(8f); setPadding(dpi(14f), 0, dpi(16f), 0)
        tag = path; contentDescription = "$name crop. $hint"; isClickable = true; isFocusable = true
        setOnClickListener {
            if (!isEnabled || working || crop.straight == straight) return@setOnClickListener
            crop.straight = straight; prefs().edit().putBoolean(STRAIGHT_KEY, straight).apply(); paintModes(); show()
        }
    }
    private fun paintModes() {
        for ((chip, straight) in listOf(straightMode to true, cornersMode to false)) {
            val on = crop.straight == straight
            chip.isSelected = on
            chip.background = rounded(if (on) 0xebffffff.toInt() else Color.TRANSPARENT, dp(20f))
            val colour = if (on) 0xff1d2433.toInt() else 0xd9ffffff.toInt()
            chip.setTextColor(colour)
            chip.setCompoundDrawablesRelative(Kit.Icon(chip.tag as String, colour, dpi(20f)).apply { setBounds(0, 0, dpi(20f), dpi(20f)) }, null, null, null)
        }
    }
    /** A look: its small preview over its name. */
    private fun lookTile(name: String, value: ScanProcessing.Look) = TextView(c).apply {
        text = name; textSize = 12.5f; typeface = Kit.Type.HEADLINE.typeface; gravity = Gravity.CENTER; maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; compoundDrawablePadding = dpi(6f)
        setCompoundDrawablesRelative(null, LookThumb(dpi(THUMB_SIDE), dp(14f), resources.displayMetrics.density).apply { setBounds(0, 0, dpi(THUMB_SIDE), dpi(THUMB_SIDE)) }, null, null)
        contentDescription = "$name look"; isClickable = true; isFocusable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x26ffffff), null, rounded(-1, dp(16f)))
        setOnClickListener {
            if (!isEnabled || working) return@setOnClickListener
            look = value; looks.forEach { paintLook(it, it === this) }; failed = false; show(); updatePreview()
        }
        paintLook(this, value == look)
    }
    private fun paintLook(tile: TextView, on: Boolean) {
        tile.isSelected = on
        tile.setTextColor(if (on) Color.WHITE else 0xa6ffffff.toInt())
        (tile.compoundDrawablesRelative[1] as LookThumb).chosen = on
    }

    /** P720: the screen reaches under the status and navigation bars; its own content keeps clear of them. */
    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets(); post { clearBars() } }
    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets { post { clearBars() }; return insets }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        post { clearBars() }
    }
    private fun clearBars() {
        val bars = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()) ?: return
        val at = IntArray(2).also { getLocationInWindow(it) }
        val window = rootView.height
        val top = max(0, bars.top - at[1]); val bottom = max(0, at[1] + height - (window - bars.bottom))
        if (column.paddingTop != top || column.paddingBottom != bottom || column.paddingLeft != bars.left || column.paddingRight != bars.right)
            column.setPadding(bars.left, top, bars.right, bottom)
    }

    companion object {
        /** B74: the crop screen's own thread, so opening it and Done never wait behind a folder sync on the shared `app.io`. */
        internal val worker: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "picture-crop").apply { isDaemon = true } }
        /** The picture under the corners; the final copy is made from the full image. */
        const val PREVIEW_EDGE = 2048
        /** The result's long edge: a phone shows it alone across the screen, a tablet beside the picture. */
        private const val PHONE_EDGE = 1600
        private const val WIDE_EDGE = 1200
        private const val THUMB_EDGE = 240
        private const val THUMB_SIDE = 64f
        private const val TOOL_HEIGHT = 116f
        private const val PANEL_MAX = 640f
        private const val STRAIGHT_KEY = "straight"
        /** P722: how long Done and Cancel wait for a busy notebook: 40 times 250 ms. */
        private const val WAITS = 40
        private const val WAIT_MS = 250L
        /** The screen's darkest colour, also given to the system bars while it shows. */
        const val BACK = 0xff0f1013.toInt()
        private const val NOTE = 0xffbfdbff.toInt()
        private const val FAULT = 0xffffb4ab.toInt()
        /** The original first, then the scan looks, so a picture keeps its colours unless asked. */
        val LOOKS = listOf(ScanProcessing.Look.ORIGINAL to "Photo", ScanProcessing.Look.DOCUMENT to "Colour",
            ScanProcessing.Look.GREY to "Greyscale", ScanProcessing.Look.BLACK_WHITE to "Black & white")
        const val WHOLE = "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"
        const val ROTATE = "M20 12a8 8 0 1 1-2.4-5.7L20 8.5M20 4v4.5h-4.5"
        /** The same arrow the other way: back to level. */
        const val RESET = "M4 12a8 8 0 1 0 2.4-5.7L4 8.5M4 4v4.5h4.5"
        /** A page outline with a corner bracket: find the page. */
        const val FIND = "M8 6.5h8.5v11H8zM3.5 7V3.5H7M17 3.5h3.5V7M20.5 17v3.5H17M7 20.5H3.5V17"
        /** A square frame: Straight. */
        const val STRAIGHT = "M5 5h14v14H5z"
        /** A slanted frame: Corners. */
        const val CORNERS = "M6 4.5l13.5 2-1.5 13-13.5-2z"
        /** The crop marks, for the Page and paper menu and the Crop tool. */
        const val CROP = "M7 3v14h14M3 7h14v14"
        private const val CROP_ICON = CROP
        /** A level: a dial with its needle, for Straighten. */
        const val LEVEL = "M3.5 16.5a8.5 8.5 0 0 1 17 0M12 16.5l4-5.5M7 16.5h10"
        /** Three overlapping circles, for the looks. */
        const val LOOKS_ICON = "M4 9.5a5 5 0 1 0 10 0a5 5 0 1 0 -10 0M10 9.5a5 5 0 1 0 10 0a5 5 0 1 0 -10 0M7 14.5a5 5 0 1 0 10 0a5 5 0 1 0 -10 0"
        /** A wand and sparks: straighten by itself. */
        const val AUTO = "M4.5 19.5l10-10M15 3.5v4M13 5.5h4M19.5 9v3M18 10.5h3"
        /** Sliders with numbers: Exact numbers. */
        const val NUMBERS = "M4 7h16M4 12h16M4 17h16M8 5v4M15 10v4M10 15v4"
        /** A cross: Cancel. */
        const val CLOSE = "M6.5 6.5l11 11M17.5 6.5l-11 11"
        /** A page holding a small picture, for Make picture. */
        const val TO_PICTURE = "M5.5 3.5h13v17h-13zM8.5 15l2.5-3 2 2 1.5-1.5 1.5 2.5zM9 8.5h6"
    }
}
