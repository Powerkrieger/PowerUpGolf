package de.powerizzle.powerupgolf.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

const val BALL_R = 1.6
const val CUP_R = 2.6
const val MAX_SHOT_SPEED = 140.0
const val DT = 1.0 / 240

private const val FUNNEL_ACCEL = 45.0
private const val MAGNET_FUNNEL_FACTOR = 2.6
private const val CAPTURE_SPEED = 60.0
private const val WALL_BOUNCE = 0.72
private const val BALL_BOUNCE = 0.92
private const val ARM_HALF_WIDTH = 1.0
private const val SLOW_SPEED = 1.2
private const val SETTLE_TIME = 0.5
private const val BOX_REACH = 2.2
private const val MINE_TRIGGER = 1.4
const val MINE_BLAST_R = 16.0
private const val TORNADO_FLING_SPEED = 85.0
private const val TORNADO_COOLDOWN = 0.8
private const val BOOST_ACCEL = 260.0

/** Rolling resistance per terrain: constant deceleration plus speed-proportional drag. */
private class Friction(val decel: Double, val drag: Double)

private val FRICTION = mapOf(
    Terrain.GRASS to Friction(28.0, 0.35),
    Terrain.SAND to Friction(95.0, 2.5),
    Terrain.ICE to Friction(3.0, 0.04),
    Terrain.WATER to Friction(28.0, 0.35),
)

/** A ball during simulation. Resting balls are "settled" and ignore slopes/funnels until something hits them. */
class Body(val id: Int, var pos: Vec2, var r: Double = BALL_R) {
    var vel = Vec2.ZERO
    var onCourse = true
    var settled = true
    var magnet = false
    /** Where the ball rested before the current shot; water and out-of-bounds send it back here. */
    var restPos = pos
    var slowFor = 0.0
    /** The portal end the ball just came out of; it can't warp again until it has rolled clear of it. */
    var portalExit: Vec2? = null

    fun kick(v: Vec2) {
        vel = v
        settled = false
        slowFor = 0.0
    }
}

class Hazard(val id: Int, val type: HazardType, val pos: Vec2, val r: Double, val ownerId: Int, var roundsLeft: Int) {
    var armed = true
    val lastFling = HashMap<Int, Double>()
    /** Opponents this hazard already scored points for, so a tornado pays once per victim. */
    val victims = HashSet<Int>()
}

sealed interface PhysEvent {
    data class BallHit(val a: Int, val b: Int) : PhysEvent
    data class Sunk(val id: Int) : PhysEvent
    data class Holed(val id: Int) : PhysEvent
    data class BoxTouched(val id: Int, val box: Int) : PhysEvent
    data class Flung(val hazard: Hazard, val id: Int) : PhysEvent
    data class Blast(val hazard: Hazard, val ids: List<Int>) : PhysEvent
    data class BlockHit(val hazard: Hazard, val id: Int) : PhysEvent
}

class Physics(private val level: Level, private val rng: Random) {

    /** Advances everything by one [DT] substep. Returns true when nothing moves anymore. */
    fun step(bodies: List<Body>, hazards: List<Hazard>, boxes: BooleanArray, time: Double, out: MutableList<PhysEvent>): Boolean {
        val live = bodies.filter { it.onCourse }
        for (b in live) integrate(b, hazards)
        for (b in live) {
            collideWalls(b)
            collideBumpers(b)
            collideWindmills(b, time)
            collideSliders(b, time)
            collideBlocks(b, hazards, out)
        }
        collideBalls(live, out)
        for (b in live) {
            triggerHazards(b, live, hazards, time, out)
            warp(b)
            if (level.terrainAt(b.pos) == Terrain.WATER || !level.inside(b.pos)) {
                out += PhysEvent.Sunk(b.id)
                b.pos = b.restPos
                b.vel = Vec2.ZERO
                b.settled = true
                continue
            }
            for (i in boxes.indices) if (boxes[i] && b.pos.dist(level.boxes[i]) < b.r + BOX_REACH) out += PhysEvent.BoxTouched(b.id, i)
            val toCup = b.pos.dist(level.cup)
            if (toCup < CUP_R && b.r <= CUP_R && b.vel.len() < CAPTURE_SPEED) {
                b.onCourse = false
                b.vel = Vec2.ZERO
                b.settled = true
                out += PhysEvent.Holed(b.id)
            }
            settle(b)
        }
        return live.all { it.settled || !it.onCourse }
    }

    private fun terrainWithHazards(p: Vec2, hazards: List<Hazard>): Terrain {
        if (hazards.any { it.type == HazardType.ICE && it.pos.dist(p) < it.r }) return Terrain.ICE
        return level.terrainAt(p)
    }

    private fun integrate(b: Body, hazards: List<Hazard>) {
        if (b.settled) return
        val terrain = terrainWithHazards(b.pos, hazards)
        var acc = Vec2.ZERO
        for (s in level.slopes) if (pointInPolygon(b.pos, s.poly)) acc += s.force
        for (bo in level.boosters) if (bo.contains(b.pos)) acc += bo.dir.norm() * BOOST_ACCEL
        val funnel = if (b.magnet) level.funnel * MAGNET_FUNNEL_FACTOR else level.funnel
        val toCup = level.cup - b.pos
        val d = toCup.len()
        if (d < funnel && d > 0.05) acc += toCup / d * FUNNEL_ACCEL
        b.vel += acc * DT
        val f = FRICTION.getValue(terrain)
        val speed = b.vel.len()
        val loss = (f.decel + f.drag * speed) * DT
        b.vel = if (speed <= loss) Vec2.ZERO else b.vel * (1 - loss / speed)
        b.pos += b.vel * DT
    }

    private fun settle(b: Body) {
        if (b.settled || !b.onCourse) return
        if (b.vel.len() < SLOW_SPEED) {
            b.slowFor += DT
            // Still in the funnel with a normal sized ball: keep rolling towards the cup instead of freezing.
            val inFunnel = b.pos.dist(level.cup) < level.funnel && b.r <= CUP_R
            if (b.slowFor > SETTLE_TIME && (!inFunnel || b.slowFor > SETTLE_TIME * 6)) {
                b.vel = Vec2.ZERO
                b.settled = true
            }
        } else {
            b.slowFor = 0.0
        }
    }

    /** Pushes [b] out of a thick segment and reflects its velocity. [surfaceVel] is the obstacle's own velocity. */
    private fun collideSegment(b: Body, a: Vec2, c: Vec2, halfWidth: Double, bounce: Double, surfaceVel: (Vec2) -> Vec2 = { Vec2.ZERO }): Boolean {
        val q = closestOnSegment(b.pos, a, c)
        val d = b.pos - q
        val dist = d.len()
        val minD = b.r + halfWidth
        if (dist >= minD || dist < 1e-9) return false
        val n = d / dist
        b.pos = q + n * minD
        val sv = surfaceVel(q)
        val rel = b.vel - sv
        val vn = rel dot n
        if (vn < 0) {
            b.vel = b.vel - n * ((1 + bounce) * vn)
            if (sv.len2() > 0) {
                b.settled = false
                b.slowFor = 0.0
            }
        }
        return true
    }

    private fun collideWalls(b: Body) {
        for (w in level.allWalls) collideSegment(b, w.a, w.b, WALL_HALF_WIDTH, WALL_BOUNCE)
    }

    private fun collideCircle(b: Body, c: Vec2, r: Double, bounce: Double): Boolean {
        val d = b.pos - c
        val dist = d.len()
        val minD = b.r + r
        if (dist >= minD || dist < 1e-9) return false
        val n = d / dist
        b.pos = c + n * minD
        val vn = b.vel dot n
        if (vn < 0) b.vel = b.vel - n * ((1 + bounce) * vn)
        return true
    }

    private fun collideBumpers(b: Body) {
        for (bu in level.bumpers) collideCircle(b, bu.c, bu.r, bu.bounce)
    }

    private fun collideWindmills(b: Body, time: Double) {
        for (wm in level.windmills) {
            collideCircle(b, wm.c, 2.0, 0.5)
            for (end in wm.armEnds(time)) {
                collideSegment(b, wm.c, end, ARM_HALF_WIDTH, 0.6) { q ->
                    val r = q - wm.c
                    Vec2(-r.y, r.x) * wm.speed
                }
            }
        }
    }

    private fun collideSliders(b: Body, time: Double) {
        for (sl in level.sliders) {
            val (e1, e2) = sl.ends(time)
            val v = sl.velocity(time)
            collideSegment(b, e1, e2, SLIDER_HALF_WIDTH, 0.6) { v }
        }
    }

    private fun warp(b: Body) {
        b.portalExit?.let { if (b.pos.dist(it) > PORTAL_R + b.r) b.portalExit = null else return }
        if (b.settled) return
        for (p in level.portals) {
            val to = when {
                b.pos.dist(p.a) < PORTAL_R -> p.b
                b.pos.dist(p.b) < PORTAL_R -> p.a
                else -> continue
            }
            b.pos = to
            b.portalExit = to
            return
        }
    }

    private fun collideBlocks(b: Body, hazards: List<Hazard>, out: MutableList<PhysEvent>) {
        for (h in hazards) {
            if (h.type != HazardType.BLOCK) continue
            val wasMoving = b.vel.len2() > 1
            if (collideCircle(b, h.pos, h.r, 0.6) && wasMoving) out += PhysEvent.BlockHit(h, b.id)
        }
    }

    private fun collideBalls(live: List<Body>, out: MutableList<PhysEvent>) {
        for (i in live.indices) for (j in i + 1 until live.size) {
            val a = live[i]
            val b = live[j]
            val d = b.pos - a.pos
            val dist = d.len()
            val minD = a.r + b.r
            if (dist >= minD) continue
            val n = if (dist < 1e-9) Vec2(1.0, 0.0) else d / dist
            // Mass grows with area, so an enlarged ball bullies the others.
            val ma = a.r * a.r
            val mb = b.r * b.r
            val overlap = minD - dist
            a.pos -= n * (overlap * mb / (ma + mb))
            b.pos += n * (overlap * ma / (ma + mb))
            val vrel = (b.vel - a.vel) dot n
            if (vrel < 0) {
                val j = -(1 + BALL_BOUNCE) * vrel / (1 / ma + 1 / mb)
                a.vel -= n * (j / ma)
                b.vel += n * (j / mb)
                for (x in listOf(a, b)) {
                    x.settled = false
                    x.slowFor = 0.0
                }
                if (-vrel > 3) out += PhysEvent.BallHit(a.id, b.id)
            }
        }
    }

    private fun triggerHazards(b: Body, live: List<Body>, hazards: List<Hazard>, time: Double, out: MutableList<PhysEvent>) {
        for (h in hazards) {
            if (!h.armed) continue
            val dist = b.pos.dist(h.pos)
            when (h.type) {
                HazardType.TORNADO -> if (dist < h.r + b.r && time - (h.lastFling[b.id] ?: -99.0) > TORNADO_COOLDOWN) {
                    h.lastFling[b.id] = time
                    val a = rng.nextDouble() * 2 * PI
                    b.pos = h.pos + Vec2(cos(a), sin(a)) * (h.r * 0.5)
                    b.kick(Vec2(cos(a), sin(a)) * TORNADO_FLING_SPEED)
                    out += PhysEvent.Flung(h, b.id)
                }
                HazardType.MINE -> if (dist < MINE_TRIGGER + b.r && !b.settled) {
                    h.armed = false
                    val hit = live.filter { it.pos.dist(h.pos) < MINE_BLAST_R }
                    for (x in hit) {
                        val d = x.pos - h.pos
                        val dir = if (d.len() < 0.1) Vec2(0.0, 1.0) else d.norm()
                        x.kick(x.vel + dir * (110 * (1 - d.len() / MINE_BLAST_R) + 35))
                    }
                    out += PhysEvent.Blast(h, hit.map { it.id })
                }
                else -> {}
            }
        }
    }
}
