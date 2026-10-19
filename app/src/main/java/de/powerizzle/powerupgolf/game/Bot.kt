package de.powerizzle.powerupgolf.game

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Brute-force golfer: tries a fan of shots on a copy of the course and keeps the one ending closest
 * (by walking distance) to the cup. Used by the computer players and by the tests.
 */
class Bot(private val level: Level) {
    private val cell = 1.0
    private val cols = (level.width / cell).toInt() + 1
    private val rows = (level.height / cell).toInt() + 1
    private val dist = IntArray(cols * rows) { Int.MAX_VALUE }

    init {
        fun free(p: Vec2) = level.inside(p) && level.terrainAt(p) != Terrain.WATER &&
            level.allWalls.all { closestOnSegment(p, it.a, it.b).dist(p) > WALL_HALF_WIDTH + BALL_R * 0.8 } &&
            level.bumpers.all { it.c.dist(p) > it.r + BALL_R * 0.8 }
        val q = ArrayDeque<Int>()
        val start = idx(level.cup)
        dist[start] = 0
        q += start
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val cx = i % cols
            val cy = i / cols
            val here = Vec2(cx * cell, cy * cell)
            val next = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).mapNotNull { (dx, dy) ->
                val nx = cx + dx
                val ny = cy + dy
                if (nx !in 0 until cols || ny !in 0 until rows || !free(Vec2(nx * cell, ny * cell))) null else ny * cols + nx
            }.toMutableList()
            // Portals work both ways: standing in one end is as good as standing in the other.
            for (p in level.portals) {
                if (here.dist(p.a) < PORTAL_R) next += idx(p.b)
                if (here.dist(p.b) < PORTAL_R) next += idx(p.a)
            }
            for (n in next) {
                if (dist[n] != Int.MAX_VALUE) continue
                dist[n] = dist[i] + 1
                q += n
            }
        }
    }

    private fun idx(p: Vec2) = (p.y / cell).toInt().coerceIn(0, rows - 1) * cols + (p.x / cell).toInt().coerceIn(0, cols - 1)

    fun walkingDistance(p: Vec2): Int = dist[idx(p)]

    /**
     * Everything besides the course that changes where a shot ends: the other balls, power-up hazards and
     * the shooter's own effects. The default is an empty course and a plain ball.
     */
    class World(
        val others: List<BallState> = emptyList(),
        val hazards: List<HazardState> = emptyList(),
        val speedFactor: Double = 1.0,
        val magnet: Boolean = false,
        val radius: Double = BALL_R,
    )

    /** [score] is the walking distance to the cup where the ball stopped, -1 when it dropped. */
    data class Result(val shot: Vec2, val end: Vec2?, val score: Int)

    /** Extra walking distance charged for a splash: the stroke is lost and the ball goes back. */
    private val splashPenalty = 25

    /** Plays one shot of length 0..1 from [from] at simulation [time]. */
    fun simulate(from: Vec2, shot: Vec2, time: Double, world: World = World()): Result {
        val physics = Physics(level, Random(1))
        val me = Body(-1, from, world.radius)
        me.magnet = world.magnet
        val bodies = listOf(me) + world.others.mapIndexed { i, b -> Body(i, Vec2(b.x, b.y), b.r) }
        val hazards = world.hazards.map { Hazard(it.id, it.type, Vec2(it.x, it.y), it.r, it.ownerId, it.roundsLeft) }
        me.kick(shot.norm() * (shot.len() * MAX_SHOT_SPEED * world.speedFactor))
        val events = mutableListOf<PhysEvent>()
        var t = time
        var splashed = false
        val boxes = BooleanArray(level.boxes.size)
        for (step in 0 until (20 / DT).toInt()) {
            events.clear()
            val done = physics.step(bodies, hazards, boxes, t, events)
            t += DT
            if (events.any { it is PhysEvent.Sunk && it.id == me.id }) splashed = true
            if (!me.onCourse) return Result(shot, null, -1)
            if (done) break
        }
        val d = walkingDistance(me.pos)
        return Result(shot, me.pos, if (splashed && d != Int.MAX_VALUE) d + splashPenalty else d)
    }

    /** Every shot on a grid of [angles] directions × [powers] strengths, best first. */
    fun candidates(from: Vec2, time: Double, world: World = World(), angles: Int = 120, powers: Int = 18): List<Result> {
        val out = ArrayList<Result>(angles * powers)
        for (a in 0 until angles) {
            val angle = a * 2 * PI / angles
            for (i in 0 until powers) {
                val pw = 0.15 + 0.85 * i / (powers - 1).coerceAtLeast(1)
                out += simulate(from, Vec2(cos(angle), sin(angle)) * pw, time, world)
            }
        }
        return out.sortedBy { it.score }
    }

    /** Tries small variations around each of [seeds] and returns everything, best first. */
    fun refine(from: Vec2, time: Double, world: World, seeds: List<Result>, step: Double = 0.025): List<Result> {
        val out = ArrayList<Result>(seeds)
        for (r in seeds) {
            val a = atan2(r.shot.y, r.shot.x)
            val p = r.shot.len()
            for (da in -2..2) for (dp in -2..2) {
                if (da == 0 && dp == 0) continue
                val pw = (p * (1 + dp * step * 2)).coerceIn(0.05, 1.0)
                out += simulate(from, Vec2(cos(a + da * step), sin(a + da * step)) * pw, time, world)
            }
        }
        return out.sortedBy { it.score }
    }

    /** The first shot that drops, otherwise the one ending closest to the cup. */
    fun best(from: Vec2, time: Double, world: World = World()): Result {
        var best: Result? = null
        for (a in 0 until 120) {
            val angle = a * 2 * PI / 120
            for (pw in 3..20) {
                val r = simulate(from, Vec2(cos(angle), sin(angle)) * (pw / 20.0), time, world)
                if (best == null || r.score < best.score) best = r
                if (r.score == -1) return r
            }
        }
        return best!!
    }
}
