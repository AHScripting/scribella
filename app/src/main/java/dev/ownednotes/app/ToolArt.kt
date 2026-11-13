package dev.ownednotes.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * P173: the writing tools drawn as the physical objects they imitate, rather than named or sampled. Each tool stands
 * tip-up in a 40 x 120 unit box; the lower part is meant to disappear behind a tray lip, as pens do in a pen tray.
 * The part that carries the ink (lead, grip, nib, bristles, printed dashes, felt) is drawn in the current colour.
 */
object ToolArt {
    /** P186: LASSO and RULER stand beside the pens in the Glass palette (the board's lasso wand and ruler with set square). */
    enum class Tool { PENCIL, BALLPOINT, CALLIGRAPHY, FOUNTAIN, BRUSH, TECHNICAL, HIGHLIGHTER, ERASER, LASSO, RULER, PAINT }
    const val WIDTH = 40f
    const val LENGTH = 120f
    /** P186: the Glass palette's slots show more barrel: 160 units, as on the board. */
    const val PALETTE_LENGTH = 160f
    /** Where the barrel being drawn ends; [draw] sets it. */
    private var end = LENGTH

    fun of(kind: PenKind) = when (kind) {
        PenKind.PRESSURE -> Tool.BRUSH
        PenKind.BALLPOINT -> Tool.BALLPOINT
        PenKind.DASHED -> Tool.TECHNICAL
        PenKind.CALLIGRAPHY -> Tool.CALLIGRAPHY
        PenKind.PENCIL -> Tool.PENCIL
        PenKind.FOUNTAIN, PenKind.FOUNTAIN_SOFT -> Tool.FOUNTAIN
    }

    private const val SILVER = 0xffc4ccd4.toInt()
    private const val GOLD = 0xffdcb24e.toInt()
    private const val WHITE_BODY = 0xfff2f4f6.toInt()
    private const val DARK_BODY = 0xff2f3a47.toInt()
    private const val WOOD = 0xffe8c796.toInt()
    private const val HANDLE = 0xffcfa36c.toInt()
    private const val RUBBER = 0xfff7f2eb.toInt()
    private const val SLEEVE = 0xff3d6a96.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    fun mix(a: Int, b: Int, t: Float): Int {
        fun channel(shift: Int) = (((a shr shift) and 255) * (1 - t) + ((b shr shift) and 255) * t).toInt().coerceIn(0, 255)
        return Color.rgb(channel(16), channel(8), channel(0))
    }
    private fun shade(c: Int, t: Float) = mix(c, Color.BLACK, t)
    private fun light(c: Int, t: Float) = mix(c, Color.WHITE, t)

    /** A rounded barrel: dark rims, a soft highlight left of centre. */
    private fun cylinder(c: Int, l: Float, r: Float): Shader = LinearGradient(l, 0f, r, 0f,
        intArrayOf(shade(c, .3f), c, light(c, .42f), c, shade(c, .36f)), floatArrayOf(0f, .2f, .34f, .6f, 1f), Shader.TileMode.CLAMP)
    /** Polished metal: a sharper highlight and a second reflection near the right rim. */
    private fun metal(c: Int, l: Float, r: Float): Shader = LinearGradient(l, 0f, r, 0f,
        intArrayOf(shade(c, .4f), light(c, .72f), c, shade(c, .14f), light(c, .3f), shade(c, .44f)),
        floatArrayOf(0f, .24f, .42f, .66f, .8f, 1f), Shader.TileMode.CLAMP)

    private fun shaded(canvas: Canvas, p: Path, shader: Shader) { fill.color = Color.BLACK; fill.shader = shader; canvas.drawPath(p, fill); fill.shader = null }
    private fun rect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, shader: Shader) {
        fill.color = Color.BLACK; fill.shader = shader; canvas.drawRect(l, t, r, b, fill); fill.shader = null
    }
    /** A soft shadow under the barrel, so a lifted tool reads as standing off the tray. Covered by the barrel itself. */
    private fun shadow(canvas: Canvas, l: Float, t: Float, r: Float) {
        fill.color = Color.BLACK; fill.setShadowLayer(3.2f, .9f, 1.8f, 0x3a0f1822)
        canvas.drawRect(l + .6f, t, r - .6f, end + 8f, fill); fill.clearShadowLayer()
    }

    /** P620: a highlighter at or below this width shows the fine (bullet) tip; wider ones the chisel. */
    const val FINE_HIGHLIGHTER = 10f
    /** P625: the widths a highlighter added from the + menu takes, as a fine tip or a chisel. */
    const val FINE_MARKER = 8f
    const val CHISEL_MARKER = 18f
    /** The pen kinds in the Glass board's order; P618: one Fountain stands for both nibs. */
    val PEN_KINDS = listOf(PenKind.CALLIGRAPHY, PenKind.FOUNTAIN, PenKind.BALLPOINT, PenKind.PENCIL, PenKind.PRESSURE, PenKind.DASHED)

    /** [fine]: a highlighter draws its fine bullet tip instead of the chisel (P620). */
    fun draw(canvas: Canvas, tool: Tool, ink: Int, length: Float = LENGTH, fine: Boolean = false) {
        val c = ink or 0xff000000.toInt()
        end = length
        when (tool) {
            Tool.PENCIL -> pencil(canvas, c)
            Tool.BALLPOINT -> ballpoint(canvas, c)
            Tool.CALLIGRAPHY -> calligraphy(canvas, c)
            Tool.FOUNTAIN -> fountain(canvas, c)
            Tool.BRUSH -> brush(canvas, c)
            Tool.TECHNICAL -> technical(canvas, c)
            Tool.HIGHLIGHTER -> highlighter(canvas, c, fine)
            Tool.ERASER -> eraser(canvas)
            Tool.LASSO -> lasso(canvas, c)
            Tool.RULER -> ruler(canvas)
            Tool.PAINT -> roller(canvas, c)
        }
    }

    /** B31 (P193): the paint brush as a paint roller: a nap cover in the pen's colour on a bent wire frame and a wooden handle. */
    private fun roller(canvas: Canvas, c: Int) {
        shadow(canvas, 15f, 44f, 25f)
        rect(canvas, 15f, 44f, 25f, end, cylinder(HANDLE, 15f, 25f))
        line.color = shade(HANDLE, .28f); line.alpha = 70; line.strokeWidth = .4f
        canvas.drawLine(17.5f, 50f, 17.8f, end, line); line.alpha = 255
        rect(canvas, 15f, 40f, 25f, 46f, metal(SILVER, 15f, 25f))
        // The frame leaves the roller's right end, runs back across and drops into the ferrule.
        line.color = shade(SILVER, .2f); line.strokeWidth = 1.8f; line.strokeJoin = Paint.Join.ROUND
        path.reset(); path.moveTo(33f, 13f); path.lineTo(36f, 13f); path.lineTo(36f, 26f); path.lineTo(20f, 30f); path.lineTo(20f, 41f)
        canvas.drawPath(path, line)
        // The cover is shaded top to bottom, as a roller lying across.
        path.reset(); path.addRoundRect(RectF(4f, 5f, 33f, 21f), 4f, 4f, Path.Direction.CW)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 5f, 0f, 21f, intArrayOf(shade(c, .3f), light(c, .38f), c, shade(c, .34f)), floatArrayOf(0f, .3f, .6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill); fill.shader = null
        line.color = shade(c, .4f); line.alpha = 90; line.strokeWidth = .45f
        var x = 8f; while (x < 31f) { canvas.drawLine(x, 6.5f, x, 19.5f, line); x += 3.5f }
        line.alpha = 255
    }

    /** P186: a selection wand: a dashed loop above a slim handle. The loop is drawn in [c] (the chrome's text colour). */
    private fun lasso(canvas: Canvas, c: Int) {
        shadow(canvas, 15f, 46f, 25f)
        line.color = c; line.strokeWidth = 2.6f; line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
        path.reset(); path.moveTo(21f, 8f); path.cubicTo(34f, 8f, 37f, 30f, 23f, 34f); path.cubicTo(9f, 38f, 4f, 20f, 13f, 12f)
        canvas.drawPath(path, line); line.pathEffect = null
        path.reset(); path.addRoundRect(RectF(15f, 46f, 25f, end), 5f, 5f, Path.Direction.CW)
        shaded(canvas, path, cylinder(0xffc9cfd8.toInt(), 15f, 25f))
        fill.color = 0xff8c95a3.toInt(); canvas.drawRoundRect(15f, 46f, 25f, 56f, 5f, 5f, fill)
    }

    /** P186: Shapes and ruler: a set square in the accent tint above a marked ruler. */
    private fun ruler(canvas: Canvas) {
        shadow(canvas, 12f, 48f, 28f)
        path.reset(); path.moveTo(7f, 42f); path.lineTo(33f, 42f); path.lineTo(7f, 6f); path.close()
        fill.color = 0x290a66d9; canvas.drawPath(path, fill)
        line.color = Glass.ACCENT; line.strokeWidth = 2f; line.strokeJoin = Paint.Join.ROUND; canvas.drawPath(path, line)
        fill.color = 0xffeaf0f8.toInt(); canvas.drawRoundRect(12f, 48f, 28f, end, 3f, 3f, fill)
        line.color = 0xffb9c3d3.toInt(); line.strokeWidth = 1f; canvas.drawRoundRect(12.5f, 48.5f, 27.5f, end - .5f, 3f, 3f, line)
        line.color = 0xff8c95a3.toInt(); line.strokeWidth = 1.4f; line.strokeCap = Paint.Cap.BUTT
        var y = 60f; var long = true
        while (y < end - 6f) { canvas.drawLine(12f, y, if (long) 18f else 16f, y, line); y += 10f; long = !long }
        line.strokeCap = Paint.Cap.ROUND
    }

    /** A coloured pencil: sharpened wood cone, lead and a hexagonal barrel in the ink colour. */
    private fun pencil(canvas: Canvas, c: Int) {
        shadow(canvas, 12f, 40f, 28f)
        path.reset(); path.moveTo(16.3f, 15f); path.lineTo(23.7f, 15f); path.lineTo(28f, 45f); path.lineTo(12f, 45f); path.close()
        shaded(canvas, path, cylinder(WOOD, 12f, 28f))
        line.color = shade(WOOD, .25f); line.alpha = 90; line.strokeWidth = .45f
        canvas.drawLine(18.5f, 17.5f, 16.4f, 39f, line); canvas.drawLine(21.7f, 17.5f, 23.5f, 39f, line); line.alpha = 255
        path.reset(); path.moveTo(19.3f, 4.6f); path.quadTo(20f, 2.6f, 20.7f, 4.6f); path.lineTo(23.7f, 15.2f)
        path.quadTo(20f, 16.4f, 16.3f, 15.2f); path.close()
        shaded(canvas, path, cylinder(c, 16.3f, 23.7f))
        // The paint ends in scallops where the sharpening cone cuts each face of the hexagon.
        path.reset(); path.moveTo(12f, 43f)
        path.quadTo(14.67f, 35f, 17.33f, 43f); path.quadTo(20f, 34f, 22.67f, 43f); path.quadTo(25.33f, 35f, 28f, 43f)
        path.lineTo(28f, end); path.lineTo(12f, end); path.close()
        canvas.save(); canvas.clipPath(path)
        fill.color = shade(c, .26f); canvas.drawRect(12f, 30f, 17.33f, end, fill)
        fill.color = light(c, .14f); canvas.drawRect(17.33f, 30f, 22.67f, end, fill)
        fill.color = shade(c, .44f); canvas.drawRect(22.67f, 30f, 28f, end, fill)
        fill.color = light(c, .5f); fill.alpha = 130; canvas.drawRect(18.4f, 30f, 19.6f, end, fill)
        line.color = light(c, .35f); line.alpha = 110; line.strokeWidth = .4f
        canvas.drawLine(17.33f, 30f, 17.33f, end, line); canvas.drawLine(22.67f, 30f, 22.67f, end, line); line.alpha = 255
        canvas.restore()
    }

    /** A click ballpoint: metal cone and ball, a ribbed grip in the ink colour, a white barrel. */
    private fun ballpoint(canvas: Canvas, c: Int) {
        shadow(canvas, 13.5f, 22f, 26.5f)
        rect(canvas, 13.5f, 46f, 26.5f, end, cylinder(WHITE_BODY, 13.5f, 26.5f))
        path.reset(); path.addRoundRect(RectF(14f, 21f, 26f, 47f), 1.2f, 1.2f, Path.Direction.CW)
        shaded(canvas, path, cylinder(c, 14f, 26f))
        line.color = shade(c, .42f); line.strokeWidth = .55f
        var y = 25f; while (y < 44f) { canvas.drawLine(14.7f, y, 25.3f, y, line); y += 3f }
        rect(canvas, 13.5f, 45.4f, 26.5f, 48.2f, metal(SILVER, 13.5f, 26.5f))
        path.reset(); path.moveTo(18.3f, 7.5f); path.lineTo(21.7f, 7.5f); path.lineTo(26f, 21.8f); path.lineTo(14f, 21.8f); path.close()
        shaded(canvas, path, metal(SILVER, 14f, 26f))
        rect(canvas, 19f, 4.4f, 21f, 8f, metal(SILVER, 19f, 21f))
        fill.color = c; canvas.drawCircle(20f, 4.2f, 1.3f, fill)
    }

    /** A dip pen: gold nib with slit and breather hole, its tip wet with ink, a lacquered holder in the ink colour. */
    private fun calligraphy(canvas: Canvas, c: Int) {
        shadow(canvas, 13f, 36f, 27f)
        path.reset(); path.moveTo(14f, 50f); path.lineTo(26f, 50f); path.quadTo(27f, 58f, 27.2f, 66f); path.lineTo(27.2f, end)
        path.lineTo(12.8f, end); path.lineTo(12.8f, 66f); path.quadTo(13f, 58f, 14f, 50f); path.close()
        shaded(canvas, path, cylinder(c, 12.8f, 27.2f))
        rect(canvas, 13.6f, 35.5f, 26.4f, 51f, metal(SILVER, 13.6f, 26.4f))
        line.color = shade(SILVER, .38f); line.strokeWidth = .5f
        canvas.drawLine(13.8f, 38.5f, 26.2f, 38.5f, line); canvas.drawLine(13.8f, 48f, 26.2f, 48f, line)
        path.reset(); path.moveTo(19.1f, 3.6f); path.lineTo(20.9f, 3.6f)
        path.cubicTo(22.2f, 11f, 26.4f, 17f, 25.9f, 26f); path.lineTo(25.6f, 36.5f); path.lineTo(14.4f, 36.5f); path.lineTo(14.1f, 26f)
        path.cubicTo(13.6f, 17f, 17.8f, 11f, 19.1f, 3.6f); path.close()
        shaded(canvas, path, metal(GOLD, 13.8f, 26.2f))
        canvas.save(); canvas.clipPath(path); fill.color = c; canvas.drawRect(10f, 0f, 30f, 8.4f, fill); canvas.restore()
        line.color = 0xff6e5418.toInt(); line.strokeWidth = .55f; canvas.drawLine(20f, 4.2f, 20f, 20.2f, line)
        fill.color = 0xff5a4512.toInt(); canvas.drawCircle(20f, 21.6f, 1.7f, fill)
        line.color = shade(GOLD, .32f); line.strokeWidth = .45f
        path.reset(); path.moveTo(16.4f, 31.5f); path.quadTo(20f, 27f, 23.6f, 31.5f); canvas.drawPath(path, line)
    }

    /**
     * P618: a fountain pen: a long pointed gold nib with its slit, breather hole and a bead of tipping, a black section,
     * a gold band and a barrel in the ink colour.
     */
    private fun fountain(canvas: Canvas, c: Int) {
        shadow(canvas, 13f, 34f, 27f)
        rect(canvas, 12.6f, 52f, 27.4f, end, cylinder(c, 12.6f, 27.4f))
        rect(canvas, 12.4f, 50f, 27.6f, 54.5f, metal(GOLD, 12.4f, 27.6f))
        path.reset(); path.moveTo(14.6f, 34f); path.lineTo(25.4f, 34f); path.lineTo(26.4f, 50.5f); path.lineTo(13.6f, 50.5f); path.close()
        shaded(canvas, path, cylinder(0xff1d2128.toInt(), 13.6f, 26.4f))
        path.reset(); path.moveTo(20f, 3.2f)
        path.cubicTo(21.4f, 9f, 25.8f, 17f, 25.6f, 25f); path.quadTo(25.4f, 31f, 24.6f, 35.5f); path.lineTo(15.4f, 35.5f)
        path.quadTo(14.6f, 31f, 14.4f, 25f); path.cubicTo(14.2f, 17f, 18.6f, 9f, 20f, 3.2f); path.close()
        shaded(canvas, path, metal(GOLD, 14.2f, 25.8f))
        canvas.save(); canvas.clipPath(path); fill.color = c; canvas.drawRect(10f, 0f, 30f, 5.6f, fill); canvas.restore()
        fill.color = SILVER; canvas.drawCircle(20f, 4.6f, 1.1f, fill)
        line.color = 0xff6e5418.toInt(); line.strokeWidth = .5f; canvas.drawLine(20f, 5.6f, 20f, 19.4f, line)
        fill.color = 0xff5a4512.toInt(); canvas.drawCircle(20f, 20.6f, 1.4f, fill)
        // Engraving: two arcs below the breather hole.
        line.color = shade(GOLD, .3f); line.strokeWidth = .4f
        path.reset(); path.moveTo(16.6f, 27.5f); path.quadTo(20f, 24.2f, 23.4f, 27.5f); canvas.drawPath(path, line)
        path.reset(); path.moveTo(16.2f, 30.6f); path.quadTo(20f, 27.3f, 23.8f, 30.6f); canvas.drawPath(path, line)
    }

    /** A brush for the pressure pen: bristles loaded with ink, a crimped ferrule and a wooden handle. */
    private fun brush(canvas: Canvas, c: Int) {
        shadow(canvas, 13.5f, 32f, 26.5f)
        rect(canvas, 13.5f, 44f, 26.5f, end, cylinder(HANDLE, 13.5f, 26.5f))
        line.color = shade(HANDLE, .28f); line.alpha = 70; line.strokeWidth = .4f
        canvas.drawLine(16f, 50f, 16.4f, end, line); canvas.drawLine(23.5f, 48f, 23f, end, line); line.alpha = 255
        path.reset(); path.moveTo(14.4f, 30.5f); path.lineTo(25.6f, 30.5f); path.lineTo(26.6f, 45f); path.lineTo(13.4f, 45f); path.close()
        shaded(canvas, path, metal(SILVER, 13.4f, 26.6f))
        line.color = shade(SILVER, .38f); line.strokeWidth = .5f
        canvas.drawLine(14.3f, 34f, 25.7f, 34f, line); canvas.drawLine(14.2f, 36f, 25.8f, 36f, line)
        path.reset(); path.moveTo(20f, 2.6f)
        path.cubicTo(22.2f, 8.5f, 26.6f, 15f, 26.1f, 23.5f); path.cubicTo(25.9f, 27.5f, 25f, 30f, 24.8f, 31.2f)
        path.lineTo(15.2f, 31.2f); path.cubicTo(15f, 30f, 14.1f, 27.5f, 13.9f, 23.5f)
        path.cubicTo(13.4f, 15f, 17.8f, 8.5f, 20f, 2.6f); path.close()
        shaded(canvas, path, cylinder(c, 13.9f, 26.1f))
        line.color = light(c, .55f); line.alpha = 120; line.strokeWidth = .45f
        path.reset(); path.moveTo(17f, 30f); path.quadTo(16.6f, 16f, 19.5f, 6.5f); canvas.drawPath(path, line)
        path.reset(); path.moveTo(21.4f, 30f); path.quadTo(22.4f, 18f, 20.6f, 7.5f); canvas.drawPath(path, line)
        path.reset(); path.moveTo(24f, 29f); path.quadTo(24.4f, 20f, 21.8f, 11.5f); canvas.drawPath(path, line)
        line.alpha = 255
    }

    /** A technical pen with a needle tip; a dashed line printed down its barrel marks it as the dashed pen. */
    private fun technical(canvas: Canvas, c: Int) {
        shadow(canvas, 13.5f, 30f, 26.5f)
        rect(canvas, 13.5f, 30f, 26.5f, end, cylinder(WHITE_BODY, 13.5f, 26.5f))
        fill.color = c
        var y = 36.5f; while (y < end) { canvas.drawRoundRect(17.7f, y, 22.3f, y + 7f, 2.3f, 2.3f, fill); y += 11f }
        path.reset(); path.moveTo(17.4f, 12.5f); path.lineTo(22.6f, 12.5f); path.lineTo(26.5f, 30.5f); path.lineTo(13.5f, 30.5f); path.close()
        shaded(canvas, path, cylinder(DARK_BODY, 13.5f, 26.5f))
        rect(canvas, 13.5f, 29.4f, 26.5f, 32.4f, metal(SILVER, 13.5f, 26.5f))
        rect(canvas, 19.2f, 3.8f, 20.8f, 13f, metal(SILVER, 19.2f, 20.8f))
        fill.color = c; canvas.drawCircle(20f, 3.8f, 1.05f, fill)
    }

    /**
     * P620 (board 10): a highlighter with a short tip like a real one: a barrel in its ink, a white grip, a chrome collar
     * and a short felt tip in the ink, a slanted chisel or, when [fine], a rounded bullet.
     */
    private fun highlighter(canvas: Canvas, c: Int, fine: Boolean) {
        shadow(canvas, 11f, 42f, 29f)
        rect(canvas, 11f, 74f, 29f, end + 4f, cylinder(c, 11f, 29f))
        fill.color = Color.WHITE; fill.alpha = 115; canvas.drawRect(13.5f, 80f, 16f, end, fill); fill.alpha = 255
        fill.color = Color.BLACK; fill.alpha = 31; canvas.drawRect(11f, 82f, 29f, 86f, fill); fill.alpha = 255
        path.reset(); path.moveTo(12f, 42f); path.lineTo(28f, 42f); path.lineTo(29f, 74f); path.lineTo(11f, 74f); path.close()
        shaded(canvas, path, cylinder(WHITE_BODY, 11f, 29f))
        path.reset(); path.moveTo(14f, 29f); path.lineTo(26f, 29f); path.lineTo(27.6f, 42f); path.lineTo(12.4f, 42f); path.close()
        shaded(canvas, path, metal(SILVER, 12.4f, 27.6f))
        fill.color = c
        if (fine) {
            path.reset(); path.moveTo(15.4f, 29f); path.lineTo(24.6f, 29f); path.lineTo(24.6f, 16f)
            path.cubicTo(24.6f, 10f, 22.6f, 4f, 20f, 4f); path.cubicTo(17.4f, 4f, 15.4f, 10f, 15.4f, 16f); path.close()
            canvas.drawPath(path, fill)
            path.reset(); path.moveTo(17f, 27f); path.lineTo(18.8f, 27f); path.lineTo(18.8f, 15f)
            path.cubicTo(18.8f, 12f, 19.2f, 10f, 19.8f, 8f); path.cubicTo(18f, 10f, 17f, 12.5f, 17f, 15f); path.close()
            fill.color = Color.WHITE; fill.alpha = 107; canvas.drawPath(path, fill); fill.alpha = 255
            return
        }
        path.reset(); path.moveTo(14.6f, 29f); path.lineTo(25.4f, 29f); path.lineTo(27f, 14f); path.lineTo(13f, 4f); path.close()
        canvas.drawPath(path, fill)
        path.reset(); path.moveTo(16.3f, 27f); path.lineTo(18.3f, 27f); path.lineTo(17.3f, 11.5f); path.lineTo(15.7f, 10.3f); path.close()
        fill.color = Color.WHITE; fill.alpha = 107; canvas.drawPath(path, fill)
        path.reset(); path.moveTo(13f, 4f); path.lineTo(27f, 14f); path.lineTo(27f, 11.6f); path.lineTo(13f, 1.6f); path.close()
        fill.color = Color.BLACK; fill.alpha = 41; canvas.drawPath(path, fill); fill.alpha = 255
    }

    /** A white rubber eraser in a paper sleeve, its working corner worn and a little smudged. */
    private fun eraser(canvas: Canvas) {
        shadow(canvas, 10.5f, 10f, 29.5f)
        path.reset(); path.moveTo(11f, 46f); path.lineTo(11f, 13.5f); path.quadTo(11.2f, 8f, 16.5f, 7.2f); path.lineTo(25.5f, 5.6f)
        path.quadTo(29f, 5.2f, 29f, 9.5f); path.lineTo(29f, 46f); path.close()
        shaded(canvas, path, cylinder(RUBBER, 11f, 29f))
        canvas.save(); canvas.clipPath(path); fill.color = Tokens.GRAPHITE; fill.alpha = 34; canvas.drawRect(11f, 4f, 29f, 10.5f, fill); canvas.restore()
        rect(canvas, 10.5f, 38f, 29.5f, end, cylinder(SLEEVE, 10.5f, 29.5f))
        rect(canvas, 10.5f, 50f, 29.5f, 54f, cylinder(0xfff7f9fb.toInt(), 10.5f, 29.5f))
        line.color = shade(SLEEVE, .32f); line.strokeWidth = .5f; canvas.drawLine(10.5f, 38.2f, 29.5f, 38.2f, line)
    }

    /** P187: height of the flat drawings, which share the 40 unit width. */
    const val FLAT_LENGTH = 84f

    /**
     * P187: the Glass board's flat pen drawings for the pen popover, tip-up in a 40 x 84 unit box: each kind has its own
     * colours and shape so the five read apart at a glance. Only the dashed pen's dashes take the current [ink].
     * Tools without a flat drawing fall back to [draw].
     */
    fun flat(canvas: Canvas, tool: Tool, ink: Int) {
        fun solid(c: Long) = fill.apply { shader = null; color = c.toInt() }
        fun shape(vararg xy: Float) { path.reset(); path.moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) path.lineTo(xy[i], xy[i + 1]); path.close() }
        when (tool) {
            Tool.CALLIGRAPHY -> {
                shape(20f, 2f, 27f, 24f, 25f, 36f, 15f, 36f, 13f, 24f); canvas.drawPath(path, solid(0xffd9b45a))
                line.color = 0xff8a6a22.toInt(); line.strokeWidth = 1.2f; line.strokeCap = Paint.Cap.BUTT; canvas.drawLine(20f, 8f, 20f, 28f, line)
                canvas.drawCircle(20f, 27f, 1.7f, solid(0xff8a6a22))
                canvas.drawRoundRect(13f, 36f, 27f, 56f, 2f, 2f, solid(0xff1a1c22))
                canvas.drawRoundRect(11f, 56f, 29f, 84f, 3f, 3f, solid(0xff1b2a4e))
                canvas.drawRect(11f, 58f, 29f, 62f, solid(0xffd9b45a))
            }
            Tool.FOUNTAIN -> {
                path.reset(); path.moveTo(20f, 2f); path.cubicTo(21.6f, 10f, 26.4f, 18f, 25.6f, 34f); path.lineTo(14.4f, 34f)
                path.cubicTo(13.6f, 18f, 18.4f, 10f, 20f, 2f); path.close(); canvas.drawPath(path, solid(0xffd9b45a))
                canvas.drawCircle(20f, 3.6f, 1.4f, solid(0xffc4ccd4))
                line.color = 0xff8a6a22.toInt(); line.strokeWidth = 1.1f; line.strokeCap = Paint.Cap.BUTT; canvas.drawLine(20f, 5f, 20f, 22f, line)
                canvas.drawCircle(20f, 23f, 1.6f, solid(0xff8a6a22))
                canvas.drawRoundRect(14f, 34f, 26f, 52f, 2f, 2f, solid(0xff1a1c22))
                canvas.drawRoundRect(12f, 52f, 28f, 84f, 3f, 3f, solid(0xff1f4d3a))
                canvas.drawRect(12f, 52f, 28f, 56f, solid(0xffd9b45a))
            }
            Tool.BALLPOINT -> {
                shape(20f, 3f, 22f, 12f, 18f, 12f); canvas.drawPath(path, solid(0xff7c8696))
                shape(18f, 12f, 22f, 12f, 27f, 36f, 13f, 36f); canvas.drawPath(path, solid(0xffc9cfd8))
                canvas.drawRect(13f, 36f, 27f, 62f, solid(0xff2b2f38))
                canvas.drawRect(13f, 62f, 27f, 84f, solid(0xffeceff4))
                canvas.drawRect(13f, 62f, 27f, 67f, solid(0xff111111))
            }
            Tool.PENCIL -> {
                shape(20f, 3f, 12f, 40f, 28f, 40f); canvas.drawPath(path, solid(0xffebcb9b))
                shape(20f, 3f, 16.8f, 18f, 23.2f, 18f); canvas.drawPath(path, solid(0xff3a3f47))
                canvas.drawRect(12f, 40f, 28f, 84f, solid(0xfff3b23a))
                canvas.drawRect(17.5f, 40f, 22.5f, 84f, solid(0xfff8c862))
            }
            Tool.BRUSH -> {
                path.reset(); path.moveTo(20f, 2f); path.cubicTo(23f, 12f, 26f, 24f, 25f, 38f); path.lineTo(15f, 38f)
                path.cubicTo(14f, 24f, 17f, 12f, 20f, 2f); path.close(); canvas.drawPath(path, solid(0xff141414))
                canvas.drawRect(14f, 38f, 26f, 48f, solid(0xffa7afbc))
                canvas.drawRoundRect(12f, 48f, 28f, 84f, 3f, 3f, solid(0xff2e3440))
                canvas.drawRect(12f, 52f, 28f, 56f, solid(0xffd93a3a))
            }
            Tool.TECHNICAL -> {
                canvas.drawRect(19f, 3f, 21f, 17f, solid(0xff7c8696))
                shape(17f, 17f, 23f, 17f, 26f, 34f, 14f, 34f); canvas.drawPath(path, solid(0xffc9cfd8))
                canvas.drawRoundRect(13f, 34f, 27f, 84f, 3f, 3f, solid(0xfff4f6f9))
                line.color = 0xffd3d8e0.toInt(); line.strokeWidth = 1f; canvas.drawRoundRect(13.5f, 34.5f, 26.5f, 83.5f, 3f, 3f, line)
                line.color = ink or 0xff000000.toInt(); line.strokeWidth = 3f; line.strokeCap = Paint.Cap.BUTT
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 5f), 0f); canvas.drawLine(20f, 42f, 20f, 84f, line)
            }
            else -> draw(canvas, tool, ink, FLAT_LENGTH)
        }
        line.strokeCap = Paint.Cap.ROUND; line.pathEffect = null
    }
}

/**
 * P187: one pen kind as a tile, as on the Glass board: the pen drawn flat and tip-up above its name; the chosen tile has
 * an accent edge on an accent tint. A TextView, so its name stays findable and clickable like any other option.
 */
@android.annotation.SuppressLint("AppCompatCustomView") // the app uses framework widgets throughout
internal class ToolSlot(context: Context, val tool: ToolArt.Tool, label: String) : TextView(context) {
    var ink: Int = Tokens.INK
        set(value) { if (field != value) { field = value; invalidate() } }
    private var wobble = 0f
    private var wobbling: ValueAnimator? = null
    /** P187 review: the name in accent semibold; [PenTypeChoices] lights it as its highlight passes over the tile. */
    var lit = false
        set(value) {
            if (field == value) return
            field = value
            typeface = if (value) Tokens.SANS_SEMIBOLD else Tokens.SANS_MEDIUM
            setTextColor(if (value) Kit.accentText(context.night) else ToolControls.optionInk(context.night))
        }

    init {
        text = label; tooltipText = label
        textSize = 11.5f; includeFontPadding = false
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; maxLines = 1
        // P599: on a 320 dp phone "Calligraphy" did not fit at 9 dp and showed as "hy"; a single word may go smaller rather
        // than be cut. Autosize still picks the largest size that fits, so wider screens look as before.
        shrinkToFitWords(7.5f, 12f)
        setPadding(0, 0, 0, dpi(9f))
        // No background and no ripple (P187 review): PenTypeChoices draws the cards and slides the highlight between them.
        isClickable = true; isFocusable = true
        typeface = Tokens.SANS_MEDIUM; setTextColor(ToolControls.optionInk(context.night))
    }

    /** P187 review: the pen swings on its tip, settling, as its tile is chosen. */
    fun wiggle() {
        wobbling?.cancel()
        wobbling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520L; interpolator = LinearInterpolator()
            addUpdateListener { val t = it.animatedValue as Float; wobble = sin(t * PI.toFloat() * 3f) * (1f - t); invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        wobbling?.cancel(); wobbling = null; wobble = 0f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // The board draws each pen 26 dp wide; the name below keeps at least 4 dp clear of it.
        val scale = minOf(dp(26f) / ToolArt.WIDTH, (height - dp(10f + 9f) - maxOf(dp(18f), textSize + dp(4f))) / ToolArt.FLAT_LENGTH)
        val tall = ToolArt.FLAT_LENGTH * scale
        canvas.save()
        // The pen wobbles about a point 60% down it, so its tip swings and it stays clear of its name.
        canvas.translate(width / 2f, dp(10f) + tall * .6f); canvas.rotate(wobble * 9f)
        canvas.translate(-ToolArt.WIDTH / 2 * scale, -tall * .6f); canvas.scale(scale, scale)
        ToolArt.flat(canvas, tool, ink)
        canvas.restore()
        super.onDraw(canvas)
    }

    companion object {
        /** The chosen tile rests this much larger; tiles under the moving highlight swell up to [SWELL]. */
        const val GROWTH = .08f
        const val SWELL = .13f
        /** Room a [SWELL]-swollen 104 dp tile needs around it (6.8 dp each way), so its row must not clip it. */
        const val ROOM = 7f
    }
}

/**
 * P187: the pen kinds as a row of tiles in the Glass board's order; the chosen one is marked and read out as selected.
 * P187 review: the row draws the tiles' cards and the highlight itself, so a new choice whooshes there. The highlight
 * slides like a well's pill (leading edge first, trailing edge catching up, squeezed while stretched) with a fading
 * trail, and the tiles beneath it swell in a semicircle that travels with it, so the row bulges like a lens passing from
 * the old pen to the new. The chosen tile then rests [ToolSlot.GROWTH] larger and its pen wobbles.
 */
internal class PenTypeChoices(context: Context, initial: PenStyle, private val choose: (PenKind) -> Unit) : LinearLayout(context) {
    // P618: one Fountain tile stands for both nibs; the pen menu's Nib choice picks between them.
    private val order = ToolArt.PEN_KINDS
    val slots = order.map { kind -> kind to ToolSlot(context, ToolArt.of(kind), kind.label) }
    var style = initial
        set(value) { field = value; refresh() }
    /** The tile the highlight is on or headed for; where it set off from; how far along it is (1 at rest). */
    private var target = -1
    /** P187 review: the tiles the whoosh crosses, start to end; the ones beyond it are left alone. */
    private var span = 0..0
    private val start = RectF()
    private var progress = 1f
    private var landed = true
    private var whoosh: ValueAnimator? = null
    // P201 step 3: graphite cards and a deep blue highlight at night.
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (context.night) 0xff353b47.toInt() else 0xfff1f3f7.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (context.night) 0xff2c3f5c.toInt() else 0xffe7effa.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Glass.accent(context.night) }
    private val box = RectF()
    private val goal = RectF()
    private val ahead = DecelerateInterpolator(2.2f)
    private val behind = OvershootInterpolator(1.6f)

    init {
        orientation = HORIZONTAL; contentDescription = "Pen type"; isBaselineAligned = false
        setWillNotDraw(false)
        // Tiles swell past their own bounds into this padding as the highlight passes.
        clipChildren = false; clipToPadding = false
        dpi(ToolSlot.ROOM).let { setPadding(it, it, it, it) }
        slots.forEachIndexed { index, (kind, slot) ->
            // Tapping the Fountain tile keeps whichever nib is in use.
            slot.setOnClickListener { runCatching { choose(if (kind == PenKind.FOUNTAIN && style.kind.nib != null) style.kind else kind) }.onSuccess { slot.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
            addView(slot, LayoutParams(0, dpi(if (context.largeText) 124f else 104f), 1f).apply { if (index > 0) marginStart = dpi(8f) })
        }
        refresh()
    }

    /** The choice and its spoken label change at once; only the look travels. */
    private fun refresh() {
        val kind = if (style.kind == PenKind.FOUNTAIN_SOFT) PenKind.FOUNTAIN else style.kind
        val chosen = slots.indexOfFirst { it.first == kind }
        slots.forEachIndexed { index, (kind, slot) ->
            slot.ink = style.color.toInt(); slot.isSelected = index == chosen
            slot.contentDescription = "${kind.label} pen" + if (index == chosen) ", selected" else ""
        }
        if (chosen == target) return
        if (target < 0 || !isLaidOut || !ValueAnimator.areAnimatorsEnabled()) { settle(chosen); return }
        // Set off from wherever the highlight is now, even part-way through another whoosh.
        val old = whoosh; whoosh = null; old?.cancel()
        highlight(start)
        val here = indexAt(start.centerX())
        val distance = abs(here - chosen)
        // A whoosh that is cut short may be between two tiles; both count as crossed.
        span = min(floor(here).toInt(), chosen).coerceAtLeast(0)..max(ceil(here).toInt(), chosen).coerceAtMost(slots.lastIndex)
        target = chosen; progress = 0f; landed = false
        whoosh = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (420f + 70f * distance).toLong(); interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; swell(); invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (whoosh === animation) settle(target) }
            })
            start()
        }
    }

    private fun settle(index: Int) {
        val old = whoosh; whoosh = null; old?.cancel()
        target = index; progress = 1f; landed = true
        slots.forEachIndexed { i, (_, slot) -> slot.lit = i == index; size(slot, if (i == index) 1f + ToolSlot.GROWTH else 1f) }
        invalidate()
    }

    /** Tiles swell in a semicircle around the highlight: widest and tallest mid-whoosh, only the tile itself at rest. */
    private fun swell() {
        highlight(box)
        val at = indexAt(box.centerX()); val energy = energy()
        val reach = .5f + 1.1f * energy
        val peak = ToolSlot.GROWTH + (ToolSlot.SWELL - ToolSlot.GROWTH) * energy
        slots.forEachIndexed { i, (_, slot) ->
            if (i !in span) { size(slot, 1f); slot.lit = false; return@forEachIndexed }
            val d = (i - at) / reach
            size(slot, 1f + peak * sqrt(max(0f, 1f - d * d)))
            slot.lit = abs(i - at) < .5f
        }
        if (!landed && progress >= .62f) { landed = true; slots[target].second.wiggle() }
    }

    private fun energy() = sin(PI.toFloat() * min(1f, progress / .8f))

    private fun size(slot: ToolSlot, scale: Float) { slot.scaleX = scale; slot.scaleY = scale }

    /** The target tile's highlight at rest: its tile, grown. */
    private fun rest(index: Int, out: RectF) {
        val tile = slots[index].second; val g = 1f + ToolSlot.GROWTH
        val cx = (tile.left + tile.right) / 2f; val cy = (tile.top + tile.bottom) / 2f
        out.set(cx - tile.width * g / 2, cy - tile.height * g / 2, cx + tile.width * g / 2, cy + tile.height * g / 2)
    }

    /** The highlight now, as on a well: the leading edge leaves first and the trailing edge overshoots as it catches up. */
    private fun highlight(out: RectF) {
        rest(target, goal)
        if (progress >= 1f) { out.set(goal); return }
        val lead = ahead.getInterpolation(min(1f, progress / .7f))
        val follow = behind.getInterpolation(((progress - .16f) / .84f).coerceIn(0f, 1f))
        val right = goal.centerX() > start.centerX()
        val l = mix(start.left, goal.left, if (right) follow else lead)
        val r = mix(start.right, goal.right, if (right) lead else follow)
        val squeeze = (1f / sqrt(max(r - l, 1f) / goal.width())).coerceIn(.8f, 1.04f)
        val cy = mix(start.centerY(), goal.centerY(), lead); val h = mix(start.height(), goal.height(), lead) * squeeze
        // An overshoot past either end of the row squashes against it rather than leaving the row.
        val inside = edge.strokeWidth / 2
        out.set(max(min(l, r), inside), cy - h / 2, min(max(l, r), width - inside), cy + h / 2)
    }

    /** Fractional tile index under [x], from the tiles' centres. */
    private fun indexAt(x: Float): Float {
        val centres = slots.map { (it.second.left + it.second.right) / 2f }
        val i = (0 until centres.lastIndex).firstOrNull { x <= centres[it + 1] } ?: (centres.lastIndex - 1)
        return i + (x - centres[i]) / (centres[i + 1] - centres[i])
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Rounder mid-flight, back to the tile's corners at rest. */
    private fun corner(r: RectF) = mix(dp(18f) * (1f + ToolSlot.GROWTH), min(r.width(), r.height()) / 2, .5f * energy())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Each tile's card, grown with its tile; the larger ones on top where they meet.
        slots.map { it.second }.sortedBy { it.scaleX }.forEach { tile ->
            val s = tile.scaleX; val cx = (tile.left + tile.right) / 2f; val cy = (tile.top + tile.bottom) / 2f
            box.set(cx - tile.width * s / 2, cy - tile.height * s / 2, cx + tile.width * s / 2, cy + tile.height * s / 2)
            canvas.drawRoundRect(box, dp(18f) * s, dp(18f) * s, card)
        }
        if (target < 0) return
        // The whoosh's trail: where the highlight was a moment ago, fading.
        if (progress < 1f) {
            val now = progress
            TRAIL.forEach { (lag, alpha) ->
                progress = max(0f, now - lag); highlight(box); glow.alpha = alpha
                corner(box).let { canvas.drawRoundRect(box, it, it, glow) }
            }
            progress = now; glow.alpha = 255
        }
        highlight(box)
        val r = corner(box)
        canvas.drawRoundRect(box, r, r, glow)
        box.inset(edge.strokeWidth / 2, edge.strokeWidth / 2); canvas.drawRoundRect(box, r, r, edge)
    }

    override fun onDetachedFromWindow() {
        if (target >= 0) settle(target)
        super.onDetachedFromWindow()
    }

    private companion object { val TRAIL = listOf(.12f to 70, .06f to 130) }
}
