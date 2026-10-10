package de.powerizzle.powerupgolf.game

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** 2D vector in course units. The course is 100 units wide, y grows downwards like on screen. */
@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun div(s: Double) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    fun len() = sqrt(x * x + y * y)
    fun len2() = x * x + y * y
    fun norm(): Vec2 = len().let { if (it < 1e-9) ZERO else this / it }
    /** Rotated 90° (counter-clockwise on screen). */
    fun perp() = Vec2(y, -x)
    fun dist(o: Vec2) = (this - o).len()

    companion object {
        val ZERO = Vec2(0.0, 0.0)
    }
}

fun v(x: Number, y: Number) = Vec2(x.toDouble(), y.toDouble())

/** Closest point to [p] on the segment a–b. */
fun closestOnSegment(p: Vec2, a: Vec2, b: Vec2): Vec2 {
    val ab = b - a
    val t = (((p - a) dot ab) / ab.len2()).coerceIn(0.0, 1.0)
    return a + ab * t
}

/** Even-odd point-in-polygon test. */
fun pointInPolygon(p: Vec2, poly: List<Vec2>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

fun rect(x0: Number, y0: Number, x1: Number, y1: Number) = listOf(v(x0, y0), v(x1, y0), v(x1, y1), v(x0, y1))
