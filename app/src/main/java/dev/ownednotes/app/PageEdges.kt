package dev.ownednotes.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * P693: moves each side of a page found by brightness onto the straight edge nearest it, so a page corner in shadow
 * is still found. The bright region cuts a shaded corner short; the page's edge stays a straight line from dark desk to
 * lighter paper all along, even in the shadow. Plain arithmetic on a brightness grid.
 */
internal object PageEdges {
    /**
     * [quad] holds the region's corners as x, y pairs (top-left, top-right, bottom-right, bottom-left) in grid units and
     * [inside] its pixels. Returns the corners where the found sides meet, in the same order. A side keeps its place
     * when it lies along the photo's border (the page runs off the photo), when no long straight edge is near it, or
     * when the edge found would cut into the bright region (a line of print, not the page's edge).
     */
    fun refine(lum: IntArray, w: Int, h: Int, quad: FloatArray, inside: BooleanArray): FloatArray {
        if (w < 8 || h < 8 || quad.size != 8) return quad
        val blur = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (dy in -1..1) { val row = (y + dy).coerceIn(0, h - 1) * w; for (dx in -1..1) s += lum[row + (x + dx).coerceIn(0, w - 1)] }
            blur[y * w + x] = s / 9f
        }
        fun b(x: Int, y: Int) = blur[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        // Edge pixels: where the brightness changes, which way it rises and how strongly (capped, so print does not outvote the page).
        val px = FloatArray(w * h); val py = FloatArray(w * h); val mag = FloatArray(w * h); val ux = FloatArray(w * h); val uy = FloatArray(w * h)
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            val gx = (b(x + 1, y - 1) + 2 * b(x + 1, y) + b(x + 1, y + 1) - b(x - 1, y - 1) - 2 * b(x - 1, y) - b(x - 1, y + 1)) / 4
            val gy = (b(x - 1, y + 1) + 2 * b(x, y + 1) + b(x + 1, y + 1) - b(x - 1, y - 1) - 2 * b(x, y - 1) - b(x + 1, y - 1)) / 4
            val m = hypot(gx, gy)
            if (m > EDGE) { px[n] = x + .5f; py[n] = y + .5f; mag[n] = min(m, CAP); ux[n] = gx / m; uy[n] = gy / m; n++ }
        }
        val cx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4; val cy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4
        val reach = .2f * max(w, h)
        // Each side as a line: a point on it (ax, ay), its outward normal (mx, my) and the shift along that normal.
        val lines = Array(4) { FloatArray(5) }
        val sel = IntArray(n)
        for (i in 0 until 4) {
            val ax = quad[i * 2]; val ay = quad[i * 2 + 1]; val bx = quad[(i + 1) % 4 * 2]; val by = quad[(i + 1) % 4 * 2 + 1]
            val len = hypot(bx - ax, by - ay)
            var nx = 0f; var ny = 0f
            if (len >= 1f) { nx = (by - ay) / len; ny = -(bx - ax) / len; if ((ax - cx) * nx + (ay - cy) * ny < 0) { nx = -nx; ny = -ny } }
            lines[i] = floatArrayOf(ax, ay, nx, ny, 0f)
            if (len < 1f) continue
            val border = (ax < 2 && bx < 2) || (ay < 2 && by < 2) || (ax > w - 2 && bx > w - 2) || (ay > h - 2 && by > h - 2)
            if (border) continue
            val dx = (bx - ax) / len; val dy = (by - ay) / len
            // Edge pixels near this side whose brightness rises towards the page.
            var m = 0
            for (j in 0 until n) {
                val s = (px[j] - ax) * nx + (py[j] - ay) * ny; val t = (px[j] - ax) * dx + (py[j] - ay) * dy
                if (abs(s) <= reach && t >= -.15f * len && t <= 1.15f * len && -(ux[j] * nx + uy[j] * ny) >= .8f) sel[m++] = j
            }
            if (m == 0) continue
            // The straightest line through them: tilts up to 30° either way (a deep shadow can cut a corner that much),
            // shifts up to the search reach.
            val r = (reach + .45f * len).toInt() + 2
            val acc = FloatArray(2 * r + 1)
            var votes = -1f; var rho = 0f; var mx = nx; var my = ny
            for (k in -60..60) {
                val phi = Math.toRadians(k * .5).toFloat(); val c = cos(phi); val sn = sin(phi)
                val tx = nx * c - ny * sn; val ty = nx * sn + ny * c
                acc.fill(0f)
                for (q in 0 until m) { val j = sel[q]; val idx = ((px[j] - ax) * tx + (py[j] - ay) * ty).roundToInt() + r; if (idx in acc.indices) acc[idx] += mag[j] }
                for (idx in 1 until 2 * r) { val v = acc[idx - 1] + acc[idx] + acc[idx + 1]; if (v > votes) { votes = v; rho = (idx - r).toFloat(); mx = tx; my = ty } }
            }
            // How much of the side's length the line's own edge pixels cover.
            val along = HashSet<Int>()
            for (j in 0 until n) {
                val dist = (px[j] - ax) * mx + (py[j] - ay) * my - rho
                if (abs(dist) <= 1.5f && -(ux[j] * mx + uy[j] * my) >= .8f) along.add(((px[j] - ax) * -my + (py[j] - ay) * mx).roundToInt())
            }
            if (along.size < COVER * len) continue
            // The page's edge has the bright region inside it; a line of print would leave some of the region outside.
            var all = 0; var out = 0
            for (idx in inside.indices) if (inside[idx]) {
                all++
                if ((idx % w + .5f - ax) * mx + (idx / w + .5f - ay) * my - rho > 2f) out++
            }
            if (all == 0 || out > all * .01f) continue
            lines[i] = floatArrayOf(ax, ay, mx, my, rho)
        }
        val corners = FloatArray(8)
        val limit = .3f * max(w, h)
        for (i in 0 until 4) {
            val l1 = lines[(i + 3) % 4]; val l2 = lines[i]
            val c1 = l1[0] * l1[2] + l1[1] * l1[3] + l1[4]; val c2 = l2[0] * l2[2] + l2[1] * l2[3] + l2[4]
            val det = l1[2] * l2[3] - l1[3] * l2[2]
            if (abs(det) < 1e-4f) return quad
            val x = (c1 * l2[3] - l1[3] * c2) / det; val y = (l1[2] * c2 - c1 * l2[2]) / det
            if (!x.isFinite() || !y.isFinite() || hypot(x - quad[i * 2], y - quad[i * 2 + 1]) > limit) return quad
            corners[i * 2] = x.coerceIn(0f, w.toFloat()); corners[i * 2 + 1] = y.coerceIn(0f, h.toFloat())
        }
        return corners
    }
    private const val EDGE = 10f
    private const val CAP = 60f
    private const val COVER = .4f
}
