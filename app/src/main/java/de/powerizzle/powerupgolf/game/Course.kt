package de.powerizzle.powerupgolf.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class Terrain { GRASS, SAND, WATER, ICE }

/** A wall segment; balls bounce off it. Walls are drawn and collide with [WALL_HALF_WIDTH] thickness. */
data class Wall(val a: Vec2, val b: Vec2)

/** A round bouncy obstacle. [bounce] > 1 adds energy, like a pinball bumper. */
data class Bumper(val c: Vec2, val r: Double, val bounce: Double = 1.15)

data class Zone(val terrain: Terrain, val poly: List<Vec2>)

/** A sloped area that keeps pushing rolling balls with [force] (units/s²). */
data class Slope(val poly: List<Vec2>, val force: Vec2)

/** Rotating arms around [c]; their angle is a function of the shared simulation time. */
data class Windmill(val c: Vec2, val length: Double, val arms: Int, val speed: Double) {
    fun armEnds(time: Double): List<Vec2> = (0 until arms).map { i ->
        val a = time * speed + i * 2 * PI / arms
        c + Vec2(cos(a), sin(a)) * length
    }
}

/** Two linked rings. A rolling ball that drops into either end pops out of the other with the same velocity. */
data class Portal(val a: Vec2, val b: Vec2)

/**
 * A jigsaw blade: a bar of length 2×[half] lying on the line [a]–[b] whose centre saws back and forth
 * between a and b once every [period] seconds. Like windmills, its position is a function of simulation time.
 */
data class Slider(val a: Vec2, val b: Vec2, val half: Double, val period: Double, val phase: Double = 0.0) {
    private fun angle(time: Double) = 2 * PI * time / period + phase

    fun center(time: Double): Vec2 = a + (b - a) * (0.5 - 0.5 * cos(angle(time)))

    fun velocity(time: Double): Vec2 = (b - a) * (PI / period * sin(angle(time)))

    fun ends(time: Double): Pair<Vec2, Vec2> {
        val c = center(time)
        val d = (b - a).norm() * half
        return (c - d) to (c + d)
    }
}

/** A square speed pad of half-size [half] that keeps accelerating balls towards [dir]. */
data class Booster(val c: Vec2, val dir: Vec2, val half: Double = 4.0) {
    fun contains(p: Vec2) = abs(p.x - c.x) < half && abs(p.y - c.y) < half
}

/**
 * One hole. The [outline] polygon is the playable fairway, its edges are walls.
 * The cup sits in a funnel (radius [funnel]) that pulls slow balls in, so near-misses still drop.
 */
data class Level(
    val name: String,
    val description: String,
    val outline: List<Vec2>,
    val tee: Vec2,
    val cup: Vec2,
    val walls: List<Wall> = emptyList(),
    val bumpers: List<Bumper> = emptyList(),
    val zones: List<Zone> = emptyList(),
    val slopes: List<Slope> = emptyList(),
    val windmills: List<Windmill> = emptyList(),
    val portals: List<Portal> = emptyList(),
    val sliders: List<Slider> = emptyList(),
    val boosters: List<Booster> = emptyList(),
    val boxes: List<Vec2> = emptyList(),
    val funnel: Double = 7.0,
    /** Direction the tee row is laid out in (players stand side by side along it). */
    val teeSpread: Vec2 = v(1, 0),
) {
    val allWalls: List<Wall> = outline.indices.map { Wall(outline[it], outline[(it + 1) % outline.size]) } + walls
    val height: Double = outline.maxOf { it.y } + 10
    val width: Double = outline.maxOf { it.x } + 10

    fun terrainAt(p: Vec2): Terrain {
        // Later zones win, so a sand patch can sit inside an ice field.
        for (z in zones.asReversed()) if (pointInPolygon(p, z.poly)) return z.terrain
        return Terrain.GRASS
    }

    fun inside(p: Vec2) = pointInPolygon(p, outline)

    /** Tee spot for the [i]-th of [n] players. */
    fun teeSpot(i: Int, n: Int): Vec2 = tee + teeSpread.norm() * ((i - (n - 1) / 2.0) * TEE_SPACING)
}

const val WALL_HALF_WIDTH = 0.8
const val TEE_SPACING = 4.6
const val PORTAL_R = 3.0
const val SLIDER_HALF_WIDTH = 1.3
