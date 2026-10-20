package de.powerizzle.powerupgolf.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import de.powerizzle.powerupgolf.game.BallState
import de.powerizzle.powerupgolf.game.CUP_R
import de.powerizzle.powerupgolf.game.HazardType
import de.powerizzle.powerupgolf.game.Level
import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.PORTAL_R
import de.powerizzle.powerupgolf.game.SLIDER_HALF_WIDTH
import de.powerizzle.powerupgolf.game.TEE_SPACING
import de.powerizzle.powerupgolf.game.Terrain
import de.powerizzle.powerupgolf.game.Vec2
import de.powerizzle.powerupgolf.game.WALL_HALF_WIDTH
import de.powerizzle.powerupgolf.game.pointInPolygon
import kotlin.math.min
import kotlin.math.sin

/** Maps course units to screen pixels, fitting the whole hole on screen. */
class CourseView(val scale: Float, val ox: Float, val oy: Float) {
    fun p(v: Vec2) = Offset(ox + v.x.toFloat() * scale, oy + v.y.toFloat() * scale)
    fun p(x: Double, y: Double) = Offset(ox + x.toFloat() * scale, oy + y.toFloat() * scale)
    fun d(units: Double) = units.toFloat() * scale
    fun world(o: Offset) = Vec2(((o.x - ox) / scale).toDouble(), ((o.y - oy) / scale).toDouble())

    companion object {
        fun fit(size: Size, level: Level): CourseView {
            val s = min(size.width / level.width.toFloat(), size.height / level.height.toFloat())
            return CourseView(s, (size.width - level.width.toFloat() * s) / 2, (size.height - level.height.toFloat() * s) / 2)
        }
    }
}

private val PortalColors = listOf(Color(0xFFFF9800), Color(0xFFB388FF), Color(0xFF18FFFF))
private val Sand = Color(0xFFE8D49A)
private val Water = Color(0xFF3FA7D6)
private val Ice = Color(0xFFD6F3FF)
private val WallColor = Color(0xFF795548)
private val WallTop = Color(0xFFA1887F)

private fun CourseView.path(poly: List<Vec2>) = Path().apply {
    poly.forEachIndexed { i, v -> p(v).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
    close()
}

private fun DrawScope.centeredText(tm: TextMeasurer, text: String, center: Offset, sizePx: Float, color: Color = Color.Black, bold: Boolean = false) {
    val layout = tm.measure(text, TextStyle(fontSize = sizePx.toSp(), color = color, fontWeight = if (bold) FontWeight.Bold else null))
    drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}

/**
 * Draws one frame of the hole. [balls] are the (interpolated) ball positions by player id,
 * [clock] is wall time in seconds for purely cosmetic animation.
 */
fun DrawScope.drawCourse(
    v: CourseView,
    s: MatchState,
    balls: Map<Int, BallState>,
    simTime: Double,
    clock: Float,
    tm: TextMeasurer,
) {
    val level = s.level
    drawRect(Rough)
    val fairway = v.path(level.outline)
    drawPath(fairway, Grass)
    clipPath(fairway) {
        // Mowing stripes
        var y = 0.0
        while (y < level.height) {
            drawRect(Color(0x14FFFFFF), topLeft = v.p(0.0, y), size = Size(v.d(level.width), v.d(5.0)))
            y += 10.0
        }
        for (z in level.zones) {
            val path = v.path(z.poly)
            when (z.terrain) {
                Terrain.SAND -> {
                    drawPath(path, Sand)
                    drawPath(path, Color(0x22000000), style = Stroke(v.d(0.4)))
                }
                Terrain.WATER -> {
                    drawPath(path, Water)
                    drawPath(path, Color(0x66FFFFFF), style = Stroke(v.d(0.8)))
                    clipPath(path) {
                        var wy = 0.0
                        while (wy < level.height) {
                            val shift = v.d(sin(clock * 1.5 + wy) * 1.5)
                            drawLine(Color(0x33FFFFFF), v.p(0.0, wy) + Offset(shift, 0f), v.p(level.width, wy) + Offset(shift, 0f), v.d(0.4))
                            wy += 4.0
                        }
                    }
                }
                Terrain.ICE -> {
                    drawPath(path, Ice)
                    drawPath(path, Color.White, style = Stroke(v.d(0.6)))
                }
                Terrain.GRASS -> {}
            }
        }
        for (sl in level.slopes) {
            drawPath(v.path(sl.poly), Color(0x1A000000))
            val dir = sl.force.norm()
            val minX = sl.poly.minOf { it.x }
            val maxX = sl.poly.maxOf { it.x }
            val minY = sl.poly.minOf { it.y }
            val maxY = sl.poly.maxOf { it.y }
            // Chevrons drift along the slope so it reads as "downhill this way".
            val drift = ((clock * 2) % 8).toDouble()
            var gy = minY + 4
            while (gy < maxY) {
                var gx = minX + 4
                while (gx < maxX) {
                    val c = Vec2(gx, gy) + dir * (drift - 4)
                    if (pointInPolygon(c, sl.poly)) {
                        val tip = c + dir * 1.5
                        val side = dir.perp() * 1.2
                        drawLine(Color(0x55FFFFFF), v.p(c - dir * 1.0 + side), v.p(tip), v.d(0.5), StrokeCap.Round)
                        drawLine(Color(0x55FFFFFF), v.p(c - dir * 1.0 - side), v.p(tip), v.d(0.5), StrokeCap.Round)
                    }
                    gx += 8
                }
                gy += 8
            }
        }
        // Speed pads: orange tiles with chevrons racing towards the boost direction.
        for (bo in level.boosters) {
            val dir = bo.dir.norm()
            val side = dir.perp()
            drawRoundRect(
                Color(0xFFFF9800), v.p(bo.c - Vec2(bo.half, bo.half)), Size(v.d(bo.half * 2), v.d(bo.half * 2)), CornerRadius(v.d(1.0)),
            )
            drawRoundRect(
                Color(0xFFE65100), v.p(bo.c - Vec2(bo.half, bo.half)), Size(v.d(bo.half * 2), v.d(bo.half * 2)), CornerRadius(v.d(1.0)),
                style = Stroke(v.d(0.4)),
            )
            for (i in 0 until 3) {
                val t = ((clock * 1.5f + i / 3f) % 1f).toDouble()
                val c = bo.c + dir * (bo.half * (t * 1.4 - 0.7))
                val fade = Color.White.copy(alpha = (0.9 * sin(t * Math.PI)).toFloat())
                drawLine(fade, v.p(c - dir * 1.0 + side * 1.8), v.p(c + dir * 0.6), v.d(0.6), StrokeCap.Round)
                drawLine(fade, v.p(c - dir * 1.0 - side * 1.8), v.p(c + dir * 0.6), v.d(0.6), StrokeCap.Round)
            }
        }
    }

    // Portals: both ends of a pair share a colour and swirl.
    level.portals.forEachIndexed { i, pt ->
        val col = PortalColors[i % PortalColors.size]
        for (end in listOf(pt.a, pt.b)) {
            val c = v.p(end)
            val r = v.d(PORTAL_R)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF1A0033), col.copy(alpha = 0.6f)), c, r), r, c)
            for (k in 0 until 3) {
                val rr = r * (0.45f + 0.25f * k)
                drawArc(
                    col, clock * 300f * (if (k % 2 == 0) 1 else -1) + k * 90f, 200f, false,
                    topLeft = c - Offset(rr, rr), size = Size(rr * 2, rr * 2), style = Stroke(v.d(0.35), cap = StrokeCap.Round),
                )
            }
            drawCircle(col, r, c, style = Stroke(v.d(0.6)))
        }
    }

    // Tee mat
    val td = level.teeSpread.norm() * TEE_SPACING
    drawLine(Color(0x33FFFFFF), v.p(level.tee - td), v.p(level.tee + td), v.d(5.0), StrokeCap.Round)

    // The cup sits in a dip: darker towards the middle.
    val cup = v.p(level.cup)
    drawCircle(Brush.radialGradient(listOf(Color(0x66000000), Color(0x00000000)), cup, v.d(level.funnel)), v.d(level.funnel), cup)
    drawCircle(Color(0x22FFFFFF), v.d(level.funnel), cup, style = Stroke(v.d(0.3), pathEffect = PathEffect.dashPathEffect(floatArrayOf(v.d(1.0), v.d(1.0)))))
    drawCircle(Color(0xFF0E2410), v.d(CUP_R), cup)
    drawCircle(Color(0x55FFFFFF), v.d(CUP_R), cup, style = Stroke(v.d(0.3)))

    // Walls
    drawPath(fairway, WallColor, style = Stroke(v.d(WALL_HALF_WIDTH * 2), join = StrokeJoin.Round))
    drawPath(fairway, WallTop, style = Stroke(v.d(WALL_HALF_WIDTH * 0.7), join = StrokeJoin.Round))
    for (w in level.walls) {
        drawLine(WallColor, v.p(w.a), v.p(w.b), v.d(WALL_HALF_WIDTH * 2), StrokeCap.Round)
        drawLine(WallTop, v.p(w.a), v.p(w.b), v.d(WALL_HALF_WIDTH * 0.7), StrokeCap.Round)
    }
    for (b in level.bumpers) {
        drawCircle(Color(0x33000000), v.d(b.r), v.p(b.c) + Offset(v.d(0.4), v.d(0.5)))
        drawCircle(Color(0xFFE53935), v.d(b.r), v.p(b.c))
        drawCircle(Color.White, v.d(b.r * 0.75), v.p(b.c), style = Stroke(v.d(0.5)))
        drawCircle(Color(0xFFFF8A80), v.d(b.r * 0.3), v.p(b.c))
    }
    for (wm in level.windmills) {
        for (end in wm.armEnds(simTime)) {
            drawLine(Color(0x33000000), v.p(wm.c) + Offset(v.d(0.4), v.d(0.5)), v.p(end) + Offset(v.d(0.4), v.d(0.5)), v.d(2.0), StrokeCap.Round)
            drawLine(Color(0xFF6D4C41), v.p(wm.c), v.p(end), v.d(2.0), StrokeCap.Round)
            drawLine(Color(0xFFBCAAA4), v.p(wm.c), v.p(end), v.d(0.6), StrokeCap.Round)
        }
        drawCircle(Color(0xFF4E342E), v.d(2.0), v.p(wm.c))
    }

    // Jigsaw blades: a slot showing the track, and a toothed steel bar sawing along it.
    for (sl in level.sliders) {
        val dir = (sl.b - sl.a).norm()
        drawLine(Color(0x44000000), v.p(sl.a - dir * sl.half), v.p(sl.b + dir * sl.half), v.d(0.8), StrokeCap.Round)
        val (e1, e2) = sl.ends(simTime)
        val side = dir.perp() * SLIDER_HALF_WIDTH
        val len = sl.half * 2
        val teeth = (len / 1.6).toInt().coerceAtLeast(2)
        val blade = Path().apply {
            v.p(e1 + side).let { moveTo(it.x, it.y) }
            for (k in 0 until teeth) {
                val t0 = e1 + dir * (len * k / teeth)
                val t1 = e1 + dir * (len * (k + 0.5) / teeth)
                v.p(t0 + side).let { lineTo(it.x, it.y) }
                v.p(t1 + side * 1.6).let { lineTo(it.x, it.y) }
            }
            v.p(e2 + side).let { lineTo(it.x, it.y) }
            for (k in teeth - 1 downTo 0) {
                val t0 = e1 + dir * (len * (k + 1) / teeth)
                val t1 = e1 + dir * (len * (k + 0.5) / teeth)
                v.p(t0 - side).let { lineTo(it.x, it.y) }
                v.p(t1 - side * 1.6).let { lineTo(it.x, it.y) }
            }
            v.p(e1 - side).let { lineTo(it.x, it.y) }
            close()
        }
        translate(v.d(0.4), v.d(0.5)) { drawPath(blade, Color(0x33000000)) }
        drawPath(blade, Color(0xFFB0BEC5))
        drawPath(blade, Color(0xFF455A64), style = Stroke(v.d(0.3), join = StrokeJoin.Round))
        drawLine(Color(0xFFFFC107), v.p(e1), v.p(e1 + dir * 1.2), v.d(SLIDER_HALF_WIDTH * 2), StrokeCap.Butt)
        drawLine(Color(0xFFFFC107), v.p(e2), v.p(e2 - dir * 1.2), v.d(SLIDER_HALF_WIDTH * 2), StrokeCap.Butt)
    }

    // Power-up boxes
    level.boxes.forEachIndexed { i, b ->
        if (s.boxes.getOrElse(i) { false }) {
            val bob = sin(clock * 3 + i).toFloat() * v.d(0.4)
            val c = v.p(b) + Offset(0f, bob)
            val half = v.d(2.0)
            drawRoundRect(Color(0x33000000), c - Offset(half, half) + Offset(v.d(0.3), v.d(0.6) - bob), Size(half * 2, half * 2), CornerRadius(v.d(0.8)))
            drawRoundRect(Gold, c - Offset(half, half), Size(half * 2, half * 2), CornerRadius(v.d(0.8)))
            drawRoundRect(Color(0xFFB8860B), c - Offset(half, half), Size(half * 2, half * 2), CornerRadius(v.d(0.8)), style = Stroke(v.d(0.35)))
            centeredText(tm, "?", c, v.d(2.8), Color(0xFF6D4C00), bold = true)
        }
    }

    // Hazards placed by players
    for (h in s.hazards) {
        val c = v.p(h.x, h.y)
        val r = v.d(h.r)
        when (h.type) {
            HazardType.ICE -> {
                drawCircle(Ice.copy(alpha = 0.75f), r, c)
                drawCircle(Color.White, r, c, style = Stroke(v.d(0.5)))
                centeredText(tm, "❄", c, v.d(3.0), Color(0xFF4FC3F7))
            }
            HazardType.TORNADO -> {
                drawCircle(Color(0x40607D8B), r, c)
                for (i in 0 until 3) {
                    val rr = r * (0.4f + 0.3f * i)
                    drawArc(
                        Color(0xAA455A64), clock * 400f + i * 120f, 110f, false,
                        topLeft = c - Offset(rr, rr), size = Size(rr * 2, rr * 2), style = Stroke(v.d(0.6), cap = StrokeCap.Round),
                    )
                }
            }
            HazardType.MINE -> {
                drawCircle(Color(0x44000000), v.d(MINE_DRAW_R + 1.2), c)
                drawCircle(Color(0xFF263238), v.d(MINE_DRAW_R), c)
                if ((clock * 2) % 1f < 0.5f) drawCircle(Color(0xFFFF1744), v.d(0.5), c)
            }
            HazardType.BLOCK -> {
                drawCircle(Color(0x44000000), r, c + Offset(v.d(0.5), v.d(0.6)))
                drawCircle(Color(0xFF8D8D8D), r, c)
                drawCircle(Color(0xFF616161), r, c, style = Stroke(v.d(0.5)))
                drawCircle(Color(0x55FFFFFF), r * 0.35f, c - Offset(r * 0.3f, r * 0.3f))
            }
        }
    }

    // Balls
    for (p in s.players) {
        val b = balls[p.id] ?: continue
        val c = v.p(b.x, b.y)
        val r = v.d(b.r)
        if (p.id == s.currentId) {
            val pulse = (sin(clock * 6) * 0.5f + 0.5f)
            drawCircle(Color.White.copy(alpha = 0.3f + 0.4f * pulse), r + v.d(1.0 + pulse), c, style = Stroke(v.d(0.4)))
        }
        if (p.forceUp) drawCircle(Color(0x88FF5722), r + v.d(0.9), c)
        if (p.magnet) drawCircle(Color(0x882196F3), r + v.d(0.9), c)
        drawCircle(Color(0x55000000), r, c + Offset(v.d(0.35), v.d(0.5)))
        drawCircle(Color(p.color), r, c)
        drawCircle(Color.White, r, c, style = Stroke(v.d(0.35)))
        drawCircle(Color.White.copy(alpha = 0.6f), r * 0.32f, c - Offset(r * 0.35f, r * 0.35f))
        centeredText(tm, p.name, c - Offset(0f, r + v.d(2.0)), v.d(2.2), Color.White, bold = true)
    }

    // Flag on top, so it's visible even with balls around the cup.
    val top = level.cup + Vec2(0.0, -10.0)
    drawLine(Color(0x44000000), cup + Offset(v.d(0.4), 0f), v.p(top) + Offset(v.d(3.0), v.d(2.0)), v.d(0.5))
    drawLine(Color.White, cup, v.p(top), v.d(0.5), StrokeCap.Round)
    val wave = sin(clock * 4).toDouble() * 0.8
    val flag = Path().apply {
        v.p(top).let { moveTo(it.x, it.y) }
        v.p(top + Vec2(6.0, 1.5 + wave)).let { lineTo(it.x, it.y) }
        v.p(top + Vec2(0.0, 3.6)).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(flag, Color(0xFFFF5252))
}

private const val MINE_DRAW_R = 1.5
