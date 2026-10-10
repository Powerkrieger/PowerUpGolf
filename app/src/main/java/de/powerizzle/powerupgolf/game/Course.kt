package de.powerizzle.powerupgolf.game

import kotlin.math.PI
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
