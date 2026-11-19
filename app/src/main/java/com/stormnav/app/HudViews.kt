package com.stormnav.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Hand-drawn HUD widgets for the driving screen. Everything is drawn on a Canvas so the
 * app needs no image assets and stays crisp at any size.
 */

/** A colour theme for the HUD panels and accents. */
data class HudTheme(
    val id: String, val name: String,
    val panelTop: Int, val panelBottom: Int, val rim: Int, val accent: Int, val storm: Int,
    val panelAlpha: Int = 240
)

/** Route line colours chosen to stand out against radar greens, yellows and reds. */
data class RouteColor(val id: String, val name: String, val line: Int, val casing: Int)

object HudStyle {
    val THEMES = listOf(
        HudTheme("classic", "Classic", Color.rgb(28, 32, 40), Color.rgb(6, 8, 12), Color.rgb(120, 128, 140), Color.rgb(41, 182, 246), Color.rgb(168, 85, 247)),
        HudTheme("midnight", "Midnight", Color.rgb(20, 32, 58), Color.rgb(5, 9, 22), Color.rgb(70, 100, 150), Color.rgb(122, 184, 255), Color.rgb(156, 120, 255)),
        HudTheme("forest", "Forest", Color.rgb(22, 40, 28), Color.rgb(5, 12, 7), Color.rgb(70, 115, 82), Color.rgb(105, 240, 174), Color.rgb(186, 104, 200)),
        HudTheme("sunset", "Sunset", Color.rgb(44, 26, 18), Color.rgb(14, 6, 3), Color.rgb(160, 96, 52), Color.rgb(255, 171, 64), Color.rgb(236, 64, 122)),
        HudTheme("crimson", "Crimson", Color.rgb(40, 18, 22), Color.rgb(12, 4, 6), Color.rgb(150, 50, 60), Color.rgb(255, 99, 112), Color.rgb(168, 85, 247)),
        HudTheme("glass", "Glass", Color.rgb(30, 34, 42), Color.rgb(10, 12, 16), Color.argb(170, 255, 255, 255), Color.rgb(255, 255, 255), Color.rgb(168, 85, 247), panelAlpha = 150)
    )
    val ROUTE_COLORS = listOf(
        RouteColor("blue", "Blue", Color.rgb(30, 136, 255), Color.rgb(10, 60, 140)),
        RouteColor("cyan", "Cyan", Color.rgb(0, 229, 255), Color.rgb(0, 96, 110)),
        RouteColor("magenta", "Magenta", Color.rgb(234, 64, 251), Color.rgb(100, 20, 110)),
        RouteColor("white", "White", Color.rgb(250, 250, 250), Color.rgb(60, 60, 70)),
        RouteColor("violet", "Violet", Color.rgb(140, 110, 255), Color.rgb(50, 30, 120))
    )

    var theme: HudTheme = THEMES[0]
        private set
    var route: RouteColor = ROUTE_COLORS[0]
        private set

    fun setTheme(id: String?) { theme = THEMES.firstOrNull { it.id == id } ?: THEMES[0] }
    fun setRoute(id: String?) { route = ROUTE_COLORS.firstOrNull { it.id == id } ?: ROUTE_COLORS[0] }

    val PANEL_TOP get() = theme.panelTop
    val PANEL_BOTTOM get() = theme.panelBottom
    val RIM get() = theme.rim
    val STORM_PURPLE get() = theme.storm
    val STORM_YELLOW = Color.rgb(255, 214, 10)
    val INFO_BLUE get() = theme.accent
    val ROUTE_BLUE get() = route.line
    val ROUTE_CASING get() = route.casing

    /** Dark glossy rounded panel with a thin metallic rim, like the mockup's cards. */
    fun panel(ctx: Context, radiusDp: Float, rim: Int = RIM, rimDp: Float = 1.5f): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(PANEL_TOP, PANEL_BOTTOM)).apply {
            cornerRadius = radiusDp * d
            setStroke((rimDp * d).toInt().coerceAtLeast(1), rim)
            alpha = theme.panelAlpha
        }
    }

    val BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val MEDIUM: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
}

/** Round gauge with a coloured arc, an icon, a big value and a label (rain / wind / temperature). */
class GaugeView(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { RAIN, WIND, TEMP }

    var valueText: String = "--"; set(v) { field = v; invalidate() }
    var label: String = ""; set(v) { field = v; invalidate() }
    /** 0..1 position along the arc; null draws only the dim track. */
    var fraction: Float? = null; set(v) { field = v?.coerceIn(0f, 1f); invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    private val colors: IntArray = when (kind) {
        Kind.RAIN -> intArrayOf(Color.rgb(0, 200, 83), Color.rgb(174, 234, 0), Color.rgb(255, 214, 0), Color.rgb(255, 145, 0), Color.rgb(244, 30, 30))
        Kind.WIND -> intArrayOf(Color.rgb(0, 145, 234), Color.rgb(0, 176, 255), Color.rgb(64, 196, 255), Color.rgb(128, 216, 255))
        Kind.TEMP -> intArrayOf(Color.rgb(41, 121, 255), Color.rgb(0, 229, 255), Color.rgb(255, 234, 0), Color.rgb(255, 109, 0), Color.rgb(244, 30, 30))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val r = s / 2f
        drawBezel(c, cx, cy, r, p)

        // Arc: 270° sweep opening at the bottom.
        val stroke = r * 0.13f
        val ar = r * 0.80f
        arcRect.set(cx - ar, cy - ar, cx + ar, cy + ar)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT; p.strokeWidth = stroke
        p.color = Color.argb(255, 40, 46, 56)
        c.drawArc(arcRect, 135f, 270f, false, p)
        val f = fraction
        if (f != null) {
            val positions = FloatArray(colors.size) { i -> 0.75f * i / (colors.size - 1) }
            val sweep = SweepGradient(cx, cy, colors, positions)
            // SweepGradient starts at 3 o'clock; rotate so colour 0 sits at the arc start (135°).
            val m = Matrix(); m.setRotate(135f, cx, cy); sweep.setLocalMatrix(m)
            p.shader = sweep
            c.drawArc(arcRect, 135f, 270f * f.coerceAtLeast(0.04f), false, p)
            p.shader = null
        }
        // Tick marks inside the arc.
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.018f; p.color = Color.argb(200, 230, 235, 240)
        for (i in 0..27) {
            val a = Math.toRadians(135.0 + i * 10.0)
            val major = i % 3 == 0
            val r1 = r * 0.66f; val r2 = r * (if (major) 0.57f else 0.61f)
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), p)
        }

        // Icon
        val iconCy = cy - r * 0.38f
        when (kind) {
            Kind.RAIN -> drawRainIcon(c, cx, iconCy, r * 0.26f)
            Kind.WIND -> drawWindIcon(c, cx, iconCy, r * 0.26f)
            Kind.TEMP -> drawThermometer(c, cx, iconCy, r * 0.26f)
        }

        // Value + label
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
        p.typeface = HudStyle.BOLD
        p.textSize = r * (if (valueText.length > 5) 0.33f else 0.39f)
        p.setShadowLayer(r * 0.04f, 0f, r * 0.02f, Color.BLACK)
        c.drawText(valueText, cx, cy + r * 0.25f, p)
        p.typeface = HudStyle.MEDIUM; p.textSize = r * 0.19f
        c.drawText(label, cx, cy + r * 0.56f, p)
        p.clearShadowLayer()
    }

    private fun drawRainIcon(c: Canvas, x: Float, y: Float, s: Float) {
        p.style = Paint.Style.FILL; p.shader = null; p.color = Color.rgb(144, 202, 249)
        c.drawCircle(x - s * 0.45f, y, s * 0.38f, p)
        c.drawCircle(x + s * 0.05f, y - s * 0.22f, s * 0.52f, p)
        c.drawCircle(x + s * 0.55f, y + s * 0.02f, s * 0.36f, p)
        c.drawRoundRect(RectF(x - s * 0.82f, y - s * 0.05f, x + s * 0.9f, y + s * 0.38f), s * 0.2f, s * 0.2f, p)
        p.color = Color.rgb(41, 182, 246); p.strokeWidth = s * 0.16f; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        for (dx in floatArrayOf(-0.45f, 0.05f, 0.55f)) c.drawLine(x + dx * s, y + s * 0.62f, x + dx * s - s * 0.12f, y + s * 0.92f, p)
    }

    private fun drawWindIcon(c: Canvas, x: Float, y: Float, s: Float) {
        p.style = Paint.Style.STROKE; p.shader = null; p.color = Color.rgb(64, 196, 255)
        p.strokeWidth = s * 0.17f; p.strokeCap = Paint.Cap.ROUND
        val path = Path()
        path.moveTo(x - s, y - s * 0.35f); path.lineTo(x + s * 0.35f, y - s * 0.35f)
        path.arcTo(RectF(x + s * 0.1f, y - s * 0.9f, x + s * 0.65f, y - s * 0.35f), 90f, -230f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(x - s * 1.1f, y + s * 0.05f); path.lineTo(x + s * 0.75f, y + s * 0.05f)
        path.arcTo(RectF(x + s * 0.45f, y + s * 0.05f, x + s * 1.05f, y + s * 0.65f), -90f, 230f)
        c.drawPath(path, p)
        c.drawLine(x - s * 0.8f, y + s * 0.45f, x + s * 0.1f, y + s * 0.45f, p)
    }

    private fun drawThermometer(c: Canvas, x: Float, y: Float, s: Float) {
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.12f; p.color = Color.rgb(239, 83, 80)
        c.drawRoundRect(RectF(x - s * 0.22f, y - s * 1.0f, x + s * 0.22f, y + s * 0.35f), s * 0.22f, s * 0.22f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(x, y + s * 0.58f, s * 0.36f, p)
        c.drawRect(x - s * 0.09f, y - s * 0.4f, x + s * 0.09f, y + s * 0.4f, p)
        p.strokeWidth = s * 0.08f; p.style = Paint.Style.STROKE
        for (i in 0..2) { val ty = y - s * 0.8f + i * s * 0.28f; c.drawLine(x + s * 0.38f, ty, x + s * 0.62f, ty, p) }
    }
}

/** Shared metallic bezel + black face used by the round gauges and the speedometer. */
internal fun drawBezel(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
    p.style = Paint.Style.FILL
    p.shader = LinearGradient(cx, cy - r, cx, cy + r,
        intArrayOf(Color.rgb(200, 205, 212), Color.rgb(70, 74, 82), Color.rgb(170, 175, 182)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    c.drawCircle(cx, cy, r * 0.99f, p)
    p.shader = RadialGradient(cx, cy - r * 0.2f, r, intArrayOf(Color.rgb(30, 34, 42), Color.rgb(4, 5, 8)), null, Shader.TileMode.CLAMP)
    c.drawCircle(cx, cy, r * 0.93f, p)
    p.shader = null
}

/** Analog speedometer with red needle, red top band and a big digital readout. */
class SpeedometerView(context: Context) : View(context) {
    var speed: Int? = null; set(v) { field = v; invalidate() }
    var unit: String = "mph"; set(v) { field = v; invalidate() }
    /** Dial top value: 120 for mph, 200 for km/h. */
    var maxValue: Int = 120; set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private fun angleFor(v: Float): Double = Math.toRadians(135.0 + 270.0 * (v / maxValue).coerceIn(0f, 1f))

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f; val r = s / 2f
        drawBezel(c, cx, cy, r, p)

        // Red zone over the top 20 % of the dial.
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.07f; p.color = Color.rgb(229, 28, 35)
        val rr = r * 0.84f
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawArc(rect, 135f + 270f * 0.8f, 270f * 0.2f, false, p)

        // Ticks and numbers.
        val step = if (maxValue >= 200) 10 else 5
        val labelEvery = if (maxValue >= 200) 40 else 20
        p.textAlign = Paint.Align.CENTER; p.typeface = HudStyle.BOLD
        var v = 0
        while (v <= maxValue) {
            val a = angleFor(v.toFloat())
            val major = v % labelEvery == 0
            p.style = Paint.Style.STROKE; p.color = Color.WHITE
            p.strokeWidth = if (major) r * 0.03f else r * 0.015f
            val r1 = r * 0.88f; val r2 = r * (if (major) 0.74f else 0.8f)
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), p)
            if (major) {
                p.style = Paint.Style.FILL; p.textSize = r * 0.15f
                val tr = r * 0.6f
                c.drawText(v.toString(), cx + tr * cos(a).toFloat(), cy + tr * sin(a).toFloat() + r * 0.05f, p)
            }
            v += step
        }

        // Needle
        val cur = (speed ?: 0).toFloat()
        val a = angleFor(cur)
        p.style = Paint.Style.FILL; p.color = Color.rgb(229, 28, 35)
        p.setShadowLayer(r * 0.03f, 0f, 0f, Color.BLACK)
        val tip = r * 0.86f; val base = r * 0.05f
        val nx = cos(a).toFloat(); val ny = sin(a).toFloat()
        val needle = Path().apply {
            moveTo(cx + tip * nx, cy + tip * ny)
            lineTo(cx - ny * base, cy + nx * base)
            lineTo(cx + ny * base, cy - nx * base)
            close()
        }
        c.drawPath(needle, p)
        p.clearShadowLayer()
        p.shader = RadialGradient(cx, cy, r * 0.11f, intArrayOf(Color.rgb(90, 94, 100), Color.rgb(20, 22, 26)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.11f, p); p.shader = null

        // Digital readout
        p.color = Color.WHITE; p.typeface = HudStyle.BOLD; p.textSize = r * 0.42f
        p.setShadowLayer(r * 0.04f, 0f, r * 0.02f, Color.BLACK)
        c.drawText(speed?.toString() ?: "--", cx, cy + r * 0.52f, p)
        p.typeface = HudStyle.MEDIUM; p.textSize = r * 0.17f
        c.drawText(unit, cx, cy + r * 0.74f, p)
        p.clearShadowLayer()
    }
}

/** Big white turn arrow for the top banner. */
class ManeuverIconView(context: Context) : View(context) {
    var kind: ManeuverKind = ManeuverKind.STRAIGHT; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val w = s * 0.16f
        p.strokeWidth = w
        p.setShadowLayer(s * 0.04f, 0f, s * 0.02f, Color.BLACK)
        when (kind) {
            ManeuverKind.ARRIVE -> {
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.12f, s * 0.24f, p)
                val pin = Path().apply { moveTo(cx - s * 0.2f, cy); lineTo(cx, cy + s * 0.42f); lineTo(cx + s * 0.2f, cy); close() }
                c.drawPath(pin, p)
                p.color = Color.rgb(16, 18, 22); c.drawCircle(cx, cy - s * 0.12f, s * 0.09f, p); p.color = Color.WHITE
            }
            ManeuverKind.ROUNDABOUT -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx, cy - s * 0.05f, s * 0.2f, p)
                c.drawLine(cx, cy + s * 0.15f, cx, cy + s * 0.45f, p)
                arrowHead(c, cx + s * 0.2f, cy - s * 0.3f, 0.0, s)
            }
            ManeuverKind.UTURN -> {
                p.style = Paint.Style.STROKE
                val path = Path().apply {
                    moveTo(cx + s * 0.18f, cy + s * 0.42f); lineTo(cx + s * 0.18f, cy - s * 0.08f)
                    arcTo(RectF(cx - s * 0.22f, cy - s * 0.28f, cx + s * 0.18f, cy + s * 0.12f), 0f, -180f)
                    lineTo(cx - s * 0.22f, cy + s * 0.12f)
                }
                c.drawPath(path, p)
                arrowHead(c, cx - s * 0.22f, cy + s * 0.32f, 90.0, s)
            }
            else -> {
                // Stem rises from the bottom then bends toward the turn direction.
                val angle = when (kind) {
                    ManeuverKind.RIGHT -> 90.0; ManeuverKind.LEFT -> -90.0
                    ManeuverKind.SLIGHT_RIGHT -> 45.0; ManeuverKind.SLIGHT_LEFT -> -45.0
                    ManeuverKind.SHARP_RIGHT -> 135.0; ManeuverKind.SHARP_LEFT -> -135.0
                    else -> 0.0
                }
                p.style = Paint.Style.STROKE
                val bx = cx - (angle / 180.0).toFloat() * s * 0.22f
                val by = cy - s * 0.02f
                val path = Path().apply { moveTo(bx, cy + s * 0.45f); lineTo(bx, by) }
                val rad = Math.toRadians(angle - 90.0)
                val len = if (angle == 0.0) s * 0.3f else s * 0.3f
                val ex = bx + len * cos(rad).toFloat(); val ey = by + len * sin(rad).toFloat()
                if (angle != 0.0) {
                    val ctrlY = by - s * 0.18f * cos(Math.toRadians(angle)).toFloat().coerceAtLeast(0f)
                    path.quadTo(bx, ctrlY - s * 0.06f, ex, ey)
                } else path.lineTo(ex, ey)
                c.drawPath(path, p)
                arrowHead(c, ex, ey, angle, s)
            }
        }
        p.clearShadowLayer()
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, angleDeg: Double, s: Float) {
        // Triangle pointing along angleDeg (0 = up, 90 = right).
        val rad = Math.toRadians(angleDeg - 90.0)
        val fx = cos(rad).toFloat(); val fy = sin(rad).toFloat()
        val len = s * 0.24f; val half = s * 0.2f
        val head = Path().apply {
            moveTo(x + fx * len, y + fy * len)
            lineTo(x - fy * half, y + fx * half)
            lineTo(x + fy * half, y - fx * half)
            close()
        }
        val old = p.style
        p.style = Paint.Style.FILL; c.drawPath(head, p); p.style = old
    }
}

/** Small icons drawn on canvas: car, checkered flag, lightning bolt, info circle. */
class GlyphView(context: Context, private val glyph: Glyph) : View(context) {
    enum class Glyph { CAR, FLAG, BOLT, INFO }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        when (glyph) {
            Glyph.CAR -> {
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                val body = RectF(cx - s * 0.46f, cy - s * 0.04f, cx + s * 0.46f, cy + s * 0.26f)
                c.drawRoundRect(body, s * 0.08f, s * 0.08f, p)
                val roof = Path().apply {
                    moveTo(cx - s * 0.34f, cy - s * 0.02f); lineTo(cx - s * 0.24f, cy - s * 0.28f)
                    lineTo(cx + s * 0.24f, cy - s * 0.28f); lineTo(cx + s * 0.34f, cy - s * 0.02f); close()
                }
                c.drawPath(roof, p)
                p.color = Color.rgb(16, 18, 22)
                val glass = Path().apply {
                    moveTo(cx - s * 0.26f, cy - s * 0.04f); lineTo(cx - s * 0.19f, cy - s * 0.21f)
                    lineTo(cx + s * 0.19f, cy - s * 0.21f); lineTo(cx + s * 0.26f, cy - s * 0.04f); close()
                }
                c.drawPath(glass, p)
                c.drawCircle(cx - s * 0.3f, cy + s * 0.1f, s * 0.06f, p); c.drawCircle(cx + s * 0.3f, cy + s * 0.1f, s * 0.06f, p)
                p.color = Color.WHITE
                c.drawRoundRect(RectF(cx - s * 0.4f, cy + s * 0.2f, cx - s * 0.22f, cy + s * 0.36f), s * 0.04f, s * 0.04f, p)
                c.drawRoundRect(RectF(cx + s * 0.22f, cy + s * 0.2f, cx + s * 0.4f, cy + s * 0.36f), s * 0.04f, s * 0.04f, p)
            }
            Glyph.FLAG -> {
                p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = s * 0.06f
                c.drawLine(cx - s * 0.32f, cy - s * 0.4f, cx - s * 0.32f, cy + s * 0.45f, p)
                p.style = Paint.Style.FILL
                val cell = s * 0.13f
                for (row in 0 until 4) for (col in 0 until 5) {
                    p.color = if ((row + col) % 2 == 0) Color.WHITE else Color.rgb(30, 30, 30)
                    val x = cx - s * 0.29f + col * cell
                    val wave = sin((col / 4.0) * Math.PI).toFloat() * s * 0.05f
                    val y = cy - s * 0.4f + row * cell + wave
                    c.drawRect(x, y, x + cell, y + cell, p)
                }
            }
            Glyph.BOLT -> {
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(cx, cy - s / 2, cx, cy + s / 2, Color.rgb(216, 180, 254), HudStyle.STORM_PURPLE, Shader.TileMode.CLAMP)
                val bolt = Path().apply {
                    moveTo(cx + s * 0.12f, cy - s * 0.48f); lineTo(cx - s * 0.3f, cy + s * 0.06f)
                    lineTo(cx - s * 0.02f, cy + s * 0.06f); lineTo(cx - s * 0.14f, cy + s * 0.5f)
                    lineTo(cx + s * 0.3f, cy - s * 0.1f); lineTo(cx + s * 0.02f, cy - s * 0.1f); close()
                }
                c.drawPath(bolt, p); p.shader = null
            }
            Glyph.INFO -> {
                p.style = Paint.Style.STROKE; p.color = HudStyle.INFO_BLUE; p.strokeWidth = s * 0.08f
                c.drawCircle(cx, cy, s * 0.42f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.2f, s * 0.06f, p)
                c.drawRoundRect(RectF(cx - s * 0.05f, cy - s * 0.06f, cx + s * 0.05f, cy + s * 0.26f), s * 0.03f, s * 0.03f, p)
            }
        }
    }
}

/** Blue navigation chevron with white outline, used as the "you are here" map symbol. */
fun navArrowBitmap(sizePx: Int): Bitmap {
    val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val s = sizePx.toFloat(); val cx = s / 2
    val path = Path().apply {
        moveTo(cx, s * 0.08f); lineTo(s * 0.88f, s * 0.88f); lineTo(cx, s * 0.66f); lineTo(s * 0.12f, s * 0.88f); close()
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.style = Paint.Style.FILL; p.color = Color.argb(90, 0, 0, 0)
    c.save(); c.translate(0f, s * 0.03f); c.drawPath(path, p); c.restore()
    p.shader = LinearGradient(0f, 0f, s, s, Color.rgb(100, 181, 246), Color.rgb(21, 101, 192), Shader.TileMode.CLAMP)
    c.drawPath(path, p); p.shader = null
    p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = s * 0.06f; p.strokeJoin = Paint.Join.ROUND
    c.drawPath(path, p)
    return b
}

/** What marks "you" on the map. */
enum class MarkerStyle(val label: String) { ARROW("Arrow"), CAR("Car"), TRUCK("Truck"), MINIVAN("Minivan"), MOTORCYCLE("Motorcycle") }

/**
 * Top-down vehicle icons, nose pointing up so the map can rotate them with your heading.
 * [body] is the paint colour (StormNav uses the route-line colour so they match).
 */
fun markerBitmap(style: MarkerStyle, sizePx: Int, body: Int): Bitmap {
    if (style == MarkerStyle.ARROW) return navArrowBitmap(sizePx)
    val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val s = sizePx.toFloat()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val glass = Color.rgb(24, 34, 48)
    val outline = Color.WHITE
    fun r(l: Float, t: Float, rt: Float, bt: Float) = RectF(l * s, t * s, rt * s, bt * s)
    fun fill(color: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = color }
    fun stroke(color: Int, w: Float) { p.shader = null; p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w * s }
    fun shade(rect: RectF) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(rect.left, 0f, rect.right, 0f,
            intArrayOf(darker(body, 0.75f), lighter(body, 0.25f), darker(body, 0.75f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    fun shadow(rect: RectF, radius: Float) {
        fill(Color.argb(90, 0, 0, 0)); c.drawRoundRect(RectF(rect.left + s * 0.02f, rect.top + s * 0.03f, rect.right + s * 0.02f, rect.bottom + s * 0.03f), radius, radius, p)
    }
    fun trapezoid(top: Float, bottom: Float, inTop: Float, inBottom: Float, color: Int) {
        fill(color)
        c.drawPath(Path().apply {
            moveTo(inTop * s, top * s); lineTo((1 - inTop) * s, top * s)
            lineTo((1 - inBottom) * s, bottom * s); lineTo(inBottom * s, bottom * s); close()
        }, p)
    }
    when (style) {
        MarkerStyle.CAR -> {
            val bodyR = r(0.30f, 0.08f, 0.70f, 0.92f); val rad = s * 0.12f
            shadow(bodyR, rad)
            fill(darker(body, 0.6f)); c.drawOval(r(0.25f, 0.30f, 0.32f, 0.36f), p); c.drawOval(r(0.68f, 0.30f, 0.75f, 0.36f), p) // mirrors
            shade(bodyR); c.drawRoundRect(bodyR, rad, rad, p)
            trapezoid(0.30f, 0.42f, 0.36f, 0.33f, glass)                 // windshield
            fill(lighter(body, 0.12f)); c.drawRoundRect(r(0.35f, 0.43f, 0.65f, 0.70f), s * 0.04f, s * 0.04f, p) // roof
            trapezoid(0.71f, 0.79f, 0.34f, 0.37f, glass)                 // rear window
            fill(Color.rgb(255, 241, 170)); c.drawOval(r(0.33f, 0.09f, 0.40f, 0.13f), p); c.drawOval(r(0.60f, 0.09f, 0.67f, 0.13f), p)
            fill(Color.rgb(230, 40, 40)); c.drawRect(r(0.33f, 0.89f, 0.39f, 0.91f), p); c.drawRect(r(0.61f, 0.89f, 0.67f, 0.91f), p)
            stroke(outline, 0.025f); c.drawRoundRect(bodyR, rad, rad, p)
        }
        MarkerStyle.MINIVAN -> {
            val bodyR = r(0.29f, 0.05f, 0.71f, 0.95f); val rad = s * 0.09f
            shadow(bodyR, rad)
            fill(darker(body, 0.6f)); c.drawOval(r(0.24f, 0.22f, 0.31f, 0.28f), p); c.drawOval(r(0.69f, 0.22f, 0.76f, 0.28f), p)
            shade(bodyR); c.drawRoundRect(bodyR, rad, rad, p)
            trapezoid(0.19f, 0.31f, 0.35f, 0.32f, glass)
            fill(lighter(body, 0.12f)); c.drawRoundRect(r(0.33f, 0.32f, 0.67f, 0.86f), s * 0.03f, s * 0.03f, p)
            stroke(darker(body, 0.7f), 0.012f); c.drawLine(0.33f * s, 0.55f * s, 0.67f * s, 0.55f * s, p) // sliding-door line
            fill(glass); c.drawRoundRect(r(0.34f, 0.87f, 0.66f, 0.92f), s * 0.02f, s * 0.02f, p)
            fill(Color.rgb(255, 241, 170)); c.drawOval(r(0.32f, 0.06f, 0.39f, 0.10f), p); c.drawOval(r(0.61f, 0.06f, 0.68f, 0.10f), p)
            stroke(outline, 0.025f); c.drawRoundRect(bodyR, rad, rad, p)
        }
        MarkerStyle.TRUCK -> {
            val trailer = r(0.29f, 0.32f, 0.71f, 0.97f); val cab = r(0.31f, 0.03f, 0.69f, 0.29f)
            shadow(trailer, s * 0.03f); shadow(cab, s * 0.06f)
            fill(Color.rgb(225, 228, 232)); c.drawRoundRect(trailer, s * 0.03f, s * 0.03f, p)
            stroke(Color.rgb(170, 175, 182), 0.012f)
            for (i in 1..6) { val y = 0.32f + i * 0.093f; c.drawLine(0.31f * s, y * s, 0.69f * s, y * s, p) }
            fill(Color.rgb(60, 64, 70)); c.drawRect(r(0.45f, 0.28f, 0.55f, 0.33f), p) // hitch
            fill(darker(body, 0.6f)); c.drawOval(r(0.26f, 0.13f, 0.32f, 0.19f), p); c.drawOval(r(0.68f, 0.13f, 0.74f, 0.19f), p)
            shade(cab); c.drawRoundRect(cab, s * 0.06f, s * 0.06f, p)
            fill(glass); c.drawRoundRect(r(0.35f, 0.08f, 0.65f, 0.15f), s * 0.02f, s * 0.02f, p)
            fill(Color.rgb(255, 241, 170)); c.drawOval(r(0.33f, 0.035f, 0.40f, 0.065f), p); c.drawOval(r(0.60f, 0.035f, 0.67f, 0.065f), p)
            stroke(outline, 0.022f); c.drawRoundRect(cab, s * 0.06f, s * 0.06f, p); c.drawRoundRect(trailer, s * 0.03f, s * 0.03f, p)
        }
        MarkerStyle.MOTORCYCLE -> {
            fill(Color.argb(90, 0, 0, 0)); c.drawOval(r(0.38f, 0.10f, 0.64f, 0.95f), p)
            fill(Color.rgb(30, 30, 34))
            c.drawRoundRect(r(0.455f, 0.06f, 0.545f, 0.27f), s * 0.04f, s * 0.04f, p) // front tyre
            c.drawRoundRect(r(0.45f, 0.66f, 0.55f, 0.93f), s * 0.045f, s * 0.045f, p)  // rear tyre
            stroke(Color.rgb(200, 204, 210), 0.035f); p.strokeCap = Paint.Cap.ROUND
            c.drawLine(0.28f * s, 0.30f * s, 0.72f * s, 0.30f * s, p)                     // handlebars
            val tank = r(0.39f, 0.26f, 0.61f, 0.70f)
            shade(tank); c.drawOval(tank, p)
            fill(Color.rgb(40, 40, 46)); c.drawRoundRect(r(0.42f, 0.52f, 0.58f, 0.72f), s * 0.05f, s * 0.05f, p) // seat
            fill(Color.rgb(235, 238, 242)); c.drawCircle(0.5f * s, 0.48f * s, 0.085f * s, p) // helmet
            fill(glass); c.drawArc(r(0.43f, 0.40f, 0.57f, 0.54f), 200f, 140f, true, p)       // visor
            fill(Color.rgb(255, 241, 170)); c.drawCircle(0.5f * s, 0.07f * s, 0.025f * s, p)
            stroke(outline, 0.02f); c.drawOval(tank, p)
        }
        MarkerStyle.ARROW -> {}
    }
    return b
}

private fun darker(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
private fun lighter(c: Int, f: Float) = Color.rgb(
    (Color.red(c) + (255 - Color.red(c)) * f).toInt(), (Color.green(c) + (255 - Color.green(c)) * f).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())
