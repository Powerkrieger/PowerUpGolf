package de.powerizzle.powerupgolf.game

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/** Draws every hole to app/build/level-previews/ so layouts can be eyeballed without a phone. */
class LevelPreview {
    @Test
    fun render() {
        val dir = File("build/level-previews").apply { mkdirs() }
        val k = 5.0
        Levels.all.forEachIndexed { n, level ->
            val img = BufferedImage((level.width * k).toInt(), (level.height * k).toInt(), BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            fun path(poly: List<Vec2>) = Path2D.Double().apply {
                poly.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * k, p.y * k) else lineTo(p.x * k, p.y * k) }
                closePath()
            }
            fun circle(c: Vec2, r: Double, col: Color, fill: Boolean = true) {
                g.color = col
                val x = ((c.x - r) * k).toInt()
                val y = ((c.y - r) * k).toInt()
                val d = (2 * r * k).toInt()
                if (fill) g.fillOval(x, y, d, d) else g.drawOval(x, y, d, d)
            }
            fun line(a: Vec2, b: Vec2, w: Double, col: Color) {
                g.color = col
                g.stroke = BasicStroke((w * k).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine((a.x * k).toInt(), (a.y * k).toInt(), (b.x * k).toInt(), (b.y * k).toInt())
            }
            g.color = Color(0x1B5E20); g.fillRect(0, 0, img.width, img.height)
            g.color = Color(0x4CAF50); g.fill(path(level.outline))
            for (z in level.zones) {
                g.color = when (z.terrain) { Terrain.SAND -> Color(0xE8D49A); Terrain.WATER -> Color(0x3FA7D6); Terrain.ICE -> Color(0xD6F3FF); else -> Color.GREEN }
                g.fill(path(z.poly))
            }
            for (s in level.slopes) { g.color = Color(0, 0, 0, 40); g.fill(path(s.poly)); line(centroid(s.poly), centroid(s.poly) + s.force.norm() * 5.0, 0.6, Color.WHITE) }
            for (b in level.boosters) {
                g.color = Color(0xFF9800); g.fill(path(rect(b.c.x - b.half, b.c.y - b.half, b.c.x + b.half, b.c.y + b.half)))
                line(b.c - b.dir.norm() * 3.0, b.c + b.dir.norm() * 3.0, 0.8, Color.WHITE)
            }
            circle(level.cup, level.funnel, Color(0, 0, 0, 50))
            circle(level.cup, CUP_R, Color.BLACK)
            g.color = Color(0x795548); g.stroke = BasicStroke((WALL_HALF_WIDTH * 2 * k).toFloat()); g.draw(path(level.outline))
            for (w in level.walls) line(w.a, w.b, WALL_HALF_WIDTH * 2, Color(0x795548))
            for (b in level.bumpers) circle(b.c, b.r, Color(0xE53935))
            for (wm in level.windmills) { circle(wm.c, wm.length, Color(0, 0, 0, 60), fill = false); wm.armEnds(0.0).forEach { line(wm.c, it, 2.0, Color(0x6D4C41)) } }
            val portalColors = listOf(Color(0xFF9800), Color(0x7C4DFF), Color(0x00E5FF))
            level.portals.forEachIndexed { i, p ->
                circle(p.a, PORTAL_R, portalColors[i % 3]); circle(p.b, PORTAL_R, portalColors[i % 3])
                circle(p.b, PORTAL_R * 0.5, Color.WHITE)
            }
            for (s in level.sliders) {
                line(s.a, s.b, 0.3, Color(255, 255, 255, 120))
                val (a, b) = s.ends(0.0)
                line(a, b, SLIDER_HALF_WIDTH * 2, Color(0x9E9E9E))
            }
            for (b in level.boxes) circle(b, 2.0, Color(0xFFD54F))
            for (i in 0 until MAX_PLAYERS) circle(level.teeSpot(i, MAX_PLAYERS), BALL_R, Color.WHITE)
            g.color = Color.WHITE; g.drawString("${n + 1}. ${level.name}", 6, 14)
            ImageIO.write(img, "png", File(dir, "hole${n + 1}.png"))
        }
    }

    private fun centroid(p: List<Vec2>) = p.fold(Vec2.ZERO) { a, b -> a + b } / p.size.toDouble()
}
