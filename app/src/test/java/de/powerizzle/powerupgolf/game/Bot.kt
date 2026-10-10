package de.powerizzle.powerupgolf.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Brute-force golfer for tests: tries a fan of shots and keeps the one ending closest (by walking distance) to the cup. */
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
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx !in 0 until cols || ny !in 0 until rows) continue
                val n = ny * cols + nx
                if (dist[n] != Int.MAX_VALUE || !free(Vec2(nx * cell, ny * cell))) continue
                dist[n] = dist[i] + 1
                q += n
            }
        }
    }

    private fun idx(p: Vec2) = (p.y / cell).toInt().coerceIn(0, rows - 1) * cols + (p.x / cell).toInt().coerceIn(0, cols - 1)

    fun walkingDistance(p: Vec2): Int = dist[idx(p)]

    data class Result(val shot: Vec2, val end: Vec2?, val score: Int)

    /** Simulates one ball alone on the course. Returns where it stopped, or null if it dropped. */
    fun simulate(from: Vec2, shot: Vec2, time: Double): Vec2? {
        val physics = Physics(level, Random(1))
        val b = Body(0, from)
        b.kick(shot.norm() * (shot.len() * MAX_SHOT_SPEED))
        val events = mutableListOf<PhysEvent>()
        var t = time
        repeat((20 / DT).toInt()) {
            val done = physics.step(listOf(b), emptyList(), BooleanArray(level.boxes.size), t, events)
            t += DT
            if (!b.onCourse) return null
            if (done) return b.pos
        }
        return b.pos
    }

    fun best(from: Vec2, time: Double): Result {
        var best: Result? = null
        for (a in 0 until 120) {
            val angle = a * 2 * PI / 120
            for (pw in 3..20) {
                val shot = Vec2(cos(angle), sin(angle)) * (pw / 20.0)
                val end = simulate(from, shot, time)
                val score = if (end == null) -1 else walkingDistance(end)
                if (best == null || score < best.score) best = Result(shot, end, score)
                if (score == -1) return best!!
            }
        }
        return best!!
    }
}
