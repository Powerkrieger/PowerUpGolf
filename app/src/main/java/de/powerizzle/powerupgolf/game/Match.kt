package de.powerizzle.powerupgolf.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Something a player wants to do. Players only send intents; the host's [Match] decides what happens. */
sealed interface Intent {
    val playerId: Int

    data class Leave(override val playerId: Int) : Intent
    data class AddBot(override val playerId: Int, val level: BotLevel) : Intent
    data class RemoveBot(override val playerId: Int, val botId: Int) : Intent
    data class Start(override val playerId: Int) : Intent
    /** (dx, dy) is the shot direction scaled by power, length 0..1. */
    data class Shoot(override val playerId: Int, val dx: Double, val dy: Double) : Intent
    data class UsePower(override val playerId: Int, val slot: Int, val targetId: Int?, val x: Double?, val y: Double?) : Intent
}

val PLAYER_COLORS = listOf(
    0xFFE53935, 0xFF1E88E5, 0xFFFDD835, 0xFF8E24AA, 0xFFFB8C00, 0xFF00ACC1, 0xFFEC407A, 0xFF6D4C41,
)

const val HOST_ID = 0
const val TURN_SECONDS = 40
const val HOLE_DONE_SECONDS = 9
private const val MAX_ROLL_SECONDS = 20.0
private const val MAX_STEPS_PER_TICK = 24
private const val ENLARGE_FACTOR = 2.1
const val FORCE_UP_FACTOR = 1.8

/**
 * The authoritative game. Lives on the host only and is driven from a single coroutine:
 * [handle] for player intents, [tick] for time passing. Time is passed in (ms) so tests can fake it.
 */
class Match(hostName: String, seed: Long = System.nanoTime()) {
    private class P(val id: Int, val name: String, val color: Long, val bot: BotLevel? = null) {
        var connected = true
        var total = 0
        var strokes = 0
        var holed = false
        var bonus = 0
        val holeScores = mutableListOf<Int>()
        val inventory = mutableListOf<PowerUp>()
        var bigShots = 0
        var forceUp = false
        var magnet = false
        val done get() = holed || strokes >= MAX_STROKES || !connected
    }

    private val rng = Random(seed)
    private val hostName = hostName
    private val players = mutableListOf<P>()
    private var nextId = HOST_ID
    private var phase = Phase.LOBBY
    private var levelIndex = 0
    private val level get() = Levels.all[levelIndex]
    private var physics = Physics(level, rng)
    private val bodies = mutableListOf<Body>()
    private val hazards = mutableListOf<Hazard>()
    private var nextHazardId = 1
    private var boxes = BooleanArray(0)
    private var boxRespawn = IntArray(0)
    private var order = listOf<Int>()
    private var currentIdx = 0
    private var round = 0
    private var deadline = 0L
    private var simTime = 0.0
    private var rollTime = 0.0
    private var lastTick = 0L
    private var accumulator = 0.0
    private var shooterId: Int? = null
    /** True while balls roll because of a power-up rather than a shot; the same player keeps aiming afterwards. */
    private var powerRoll = false
    private val hitThisShot = HashSet<Int>()
    private val feed = ArrayDeque<FeedItem>()
    private var feedSeq = 0

    val currentPhase get() = phase

    // ---- players --------------------------------------------------------------------------------

    /** Adds a player in the lobby. Returns the new id, or null when the game already started or is full. */
    fun join(name: String, color: Long, bot: BotLevel? = null): Int? {
        if (phase != Phase.LOBBY || players.size >= MAX_PLAYERS) return null
        val taken = players.map { it.color }.toSet()
        val c = if (color in taken || color !in PLAYER_COLORS) PLAYER_COLORS.first { it !in taken } else color
        val clean = name.trim().take(14).ifEmpty { "Player ${nextId + 1}" }
        val p = P(nextId++, clean, c, bot)
        players += p
        say("${p.name} joined")
        return p.id
    }

    /** Adds a computer player in the lobby. Returns its id, or null when the game already started or is full. */
    fun addBot(level: BotLevel): Int? = join(BotBrain.nameFor(level, players.map { it.name }), 0, level)

    private fun player(id: Int?) = players.firstOrNull { it.id == id }
    private fun body(id: Int?) = bodies.firstOrNull { it.id == id && it.onCourse }

    // ---- intents --------------------------------------------------------------------------------

    fun handle(intent: Intent, now: Long) {
        when (intent) {
            is Intent.Leave -> leave(intent.playerId, now)
            is Intent.AddBot -> if (intent.playerId == HOST_ID) addBot(intent.level)
            is Intent.RemoveBot -> if (intent.playerId == HOST_ID && phase == Phase.LOBBY) {
                players.removeAll { it.id == intent.botId && it.bot != null }
            }
            is Intent.Start -> if (intent.playerId == HOST_ID && phase == Phase.LOBBY && players.isNotEmpty()) startHole(0, now)
            is Intent.Shoot -> shoot(intent, now)
            is Intent.UsePower -> usePower(intent, now)
        }
    }

    private fun leave(id: Int, now: Long) {
        val p = player(id) ?: return
        if (phase == Phase.LOBBY) {
            players.remove(p)
        } else {
            p.connected = false
            bodies.removeAll { it.id == id }
        }
        say("${p.name} left")
        if (phase == Phase.AIMING && order.getOrNull(currentIdx) == id) advanceTurn(now)
    }

    private fun isTurnOf(id: Int) = phase == Phase.AIMING && order.getOrNull(currentIdx) == id

    private fun shoot(s: Intent.Shoot, now: Long) {
        if (!isTurnOf(s.playerId)) return
        val p = player(s.playerId) ?: return
        val b = body(p.id) ?: return
        val shot = Vec2(s.dx, s.dy)
        val power = shot.len().coerceIn(0.0, 1.0)
        val speed = power * MAX_SHOT_SPEED * (if (p.forceUp) FORCE_UP_FACTOR else 1.0)
        p.strokes++
        p.forceUp = false
        b.magnet = p.magnet
        p.magnet = false
        startRoll(p.id, powered = false)
        b.kick(shot.norm() * speed)
    }

    private fun startRoll(shooter: Int, powered: Boolean) {
        for (b in bodies) b.restPos = b.pos
        shooterId = shooter
        powerRoll = powered
        hitThisShot.clear()
        rollTime = 0.0
        accumulator = 0.0
        phase = Phase.ROLLING
    }

    /**
     * Anyone still in the game may fire a power-up at any moment while balls are on the course: on their
     * own turn, on someone else's, even while balls roll. The two inventory slots are the only limit.
     */
    private fun usePower(u: Intent.UsePower, now: Long) {
        if (phase != Phase.AIMING && phase != Phase.ROLLING) return
        val p = player(u.playerId)?.takeIf { it.connected } ?: return
        val power = p.inventory.getOrNull(u.slot) ?: return
        val target = player(u.targetId)
        val targetBody = body(u.targetId)
        val spot = if (u.x != null && u.y != null) Vec2(u.x, u.y) else null
        val ok = when (power.targeting) {
            Targeting.OPPONENT -> target != null && target !== p && !target.done && targetBody != null
            Targeting.PLACE -> spot != null && level.inside(spot) && level.terrainAt(spot) != Terrain.WATER
            Targeting.SELF, Targeting.ALL_OPPONENTS -> true
            Targeting.AIMER -> phase == Phase.AIMING && order.getOrNull(currentIdx) != p.id
        }
        if (!ok) return
        var roll = false
        when (power) {
            PowerUp.TORNADO -> { addHazard(HazardType.TORNADO, spot!!, 6.0, p.id, 3); roll = true }
            PowerUp.ICE -> addHazard(HazardType.ICE, spot!!, 11.0, p.id, 3)
            PowerUp.MINE -> addHazard(HazardType.MINE, spot!!, 1.5, p.id, 6)
            PowerUp.BLOCK -> {
                val r = 4.5
                val blocked = bodies.any { it.onCourse && it.pos.dist(spot!!) < r + it.r + 0.5 } ||
                    spot!!.dist(level.cup) < r + CUP_R + 2
                if (blocked) return
                addHazard(HazardType.BLOCK, spot, r, p.id, 3)
            }
            PowerUp.PORTAL -> {
                targetBody!!.pos = randomSpot(targetBody.r)
                targetBody.restPos = targetBody.pos
            }
            PowerUp.ENLARGE -> {
                target!!.bigShots = 2
                targetBody!!.r = BALL_R * ENLARGE_FACTOR
                roll = true // let the bigger ball push its neighbours out of the way
            }
            PowerUp.FORCE_UP -> target!!.forceUp = true
            PowerUp.SWAP -> {
                val mine = body(p.id) ?: return
                val tmp = mine.pos
                mine.pos = targetBody!!.pos
                targetBody.pos = tmp
            }
            PowerUp.QUAKE -> {
                val victims = bodies.filter { it.onCourse && it.id != p.id }
                for (b in victims) {
                    val a = rng.nextDouble() * 2 * PI
                    b.kick(b.vel + Vec2(cos(a), sin(a)) * (35 + rng.nextDouble() * 30))
                }
                p.bonus += Points.SABOTAGE / 2 * victims.size
                roll = true
            }
            PowerUp.MAGNET -> p.magnet = true
            PowerUp.COUNTDOWN -> deadline = minOf(deadline, now + COUNTDOWN_SECONDS * 1000L)
        }
        val victim = if (power.targeting == Targeting.AIMER) player(order.getOrNull(currentIdx)) else target
        if (power.targeting == Targeting.OPPONENT || power.targeting == Targeting.AIMER) p.bonus += Points.SABOTAGE
        p.inventory.removeAt(u.slot)
        val on = victim?.let { " on ${it.name}" } ?: ""
        say("${power.icon} ${p.name} used ${power.label}$on")
        // Already rolling: the new pushes simply join the roll in progress.
        if (roll && phase == Phase.AIMING) startRoll(p.id, powered = true)
    }

    private fun addHazard(type: HazardType, at: Vec2, r: Double, owner: Int, rounds: Int) {
        hazards += Hazard(nextHazardId++, type, at, r, owner, rounds)
    }

    /** A free grass/sand spot away from the cup, for portals. */
    private fun randomSpot(r: Double): Vec2 {
        val maxX = level.outline.maxOf { it.x }
        val maxY = level.outline.maxOf { it.y }
        repeat(400) {
            val p = Vec2(rng.nextDouble() * maxX, rng.nextDouble() * maxY)
            val free = level.inside(p) &&
                level.terrainAt(p).let { it == Terrain.GRASS || it == Terrain.SAND } &&
                p.dist(level.cup) > 30 &&
                level.allWalls.all { closestOnSegment(p, it.a, it.b).dist(p) > r + WALL_HALF_WIDTH + 1 } &&
                level.bumpers.all { it.c.dist(p) > it.r + r + 1 } &&
                level.windmills.all { it.c.dist(p) > it.length + r + 2 } &&
                level.portals.all { it.a.dist(p) > PORTAL_R + r + 2 && it.b.dist(p) > PORTAL_R + r + 2 } &&
                level.sliders.all { closestOnSegment(p, it.a, it.b).dist(p) > it.half + r + 2 } &&
                level.boosters.all { it.c.dist(p) > it.half * 1.5 + r } &&
                hazards.all { it.pos.dist(p) > it.r + r + 1 } &&
                bodies.all { !it.onCourse || it.pos.dist(p) > it.r + r + 1 }
            if (free) return p
        }
        return level.tee
    }

    // ---- flow -----------------------------------------------------------------------------------

    private fun startHole(index: Int, now: Long) {
        levelIndex = index
        physics = Physics(level, rng)
        val active = players.filter { it.connected }
        // Best score on the last hole tees off first (golf's "honour"); ties keep the previous order.
        order = if (index == 0) active.map { it.id } else active.sortedByDescending { it.holeScores.lastOrNull() ?: 0 }.map { it.id }
        for (p in players) {
            p.strokes = 0
            p.holed = false
            p.bonus = 0
            p.bigShots = 0
            p.forceUp = false
            p.magnet = false
        }
        bodies.clear()
        order.forEachIndexed { i, id -> bodies += Body(id, level.teeSpot(i, order.size)) }
        hazards.clear()
        boxes = BooleanArray(level.boxes.size) { true }
        boxRespawn = IntArray(level.boxes.size)
        round = 1
        currentIdx = 0
        say("Hole ${index + 1}: ${level.name}")
        beginTurn(now)
    }

    private fun beginTurn(now: Long) {
        phase = Phase.AIMING
        deadline = now + TURN_SECONDS * 1000L
    }

    private fun advanceTurn(now: Long) {
        val n = order.size
        for (step in 1..n) {
            val idx = (currentIdx + step) % n
            if (player(order[idx])?.done == false) {
                if (currentIdx + step >= n) newRound()
                currentIdx = idx
                beginTurn(now)
                return
            }
        }
        endHole(now)
    }

    private fun newRound() {
        round++
        hazards.forEach { it.roundsLeft-- }
        hazards.removeAll { it.roundsLeft <= 0 || !it.armed }
        for (i in boxes.indices) if (!boxes[i] && --boxRespawn[i] <= 0) boxes[i] = true
    }

    private fun endHole(now: Long) {
        for (p in players) {
            if (p.id !in order) continue
            val score = holePoints(p.strokes, p.holed) + p.bonus
            p.holeScores += score
            p.total += score
        }
        phase = Phase.HOLE_DONE
        deadline = now + HOLE_DONE_SECONDS * 1000L
    }

    /** Lets time pass: rolls balls, runs out turn timers, moves on after the scoreboard. */
    fun tick(now: Long) {
        val dt = if (lastTick == 0L) 0.0 else (now - lastTick) / 1000.0
        lastTick = now
        when (phase) {
            Phase.ROLLING -> {
                accumulator = (accumulator + dt).coerceAtMost(MAX_STEPS_PER_TICK * DT)
                while (accumulator >= DT && phase == Phase.ROLLING) {
                    accumulator -= DT
                    step(now)
                }
            }
            Phase.AIMING -> if (now > deadline) {
                val p = player(order[currentIdx])!!
                p.strokes++
                say("⏰ ${p.name} ran out of time")
                advanceTurn(now)
            }
            Phase.HOLE_DONE -> if (now > deadline) {
                if (levelIndex + 1 < Levels.all.size) startHole(levelIndex + 1, now) else phase = Phase.GAME_OVER
            }
            else -> {}
        }
    }

    /** Runs the current roll to the end at once (for tests and bots). */
    fun settleNow(now: Long) {
        while (phase == Phase.ROLLING) step(now)
    }

    private val events = mutableListOf<PhysEvent>()

    private fun step(now: Long) {
        events.clear()
        val done = physics.step(bodies, hazards, boxes, simTime, events)
        simTime += DT
        rollTime += DT
        events.forEach(::apply)
        hazards.removeAll { !it.armed }
        if (done || rollTime > MAX_ROLL_SECONDS) finishRoll(now)
    }

    private fun apply(e: PhysEvent) {
        val shooter = player(shooterId)
        when (e) {
            is PhysEvent.BallHit -> {
                val other = when (shooterId) { e.a -> e.b; e.b -> e.a; else -> null } ?: return
                if (powerRoll || !hitThisShot.add(other)) return
                shooter!!.bonus += Points.HIT
                say("💥 ${shooter.name} bumped ${player(other)?.name} +${Points.HIT}")
            }
            is PhysEvent.Sunk -> {
                val p = player(e.id) ?: return
                if (shooter != null && p !== shooter) {
                    shooter.bonus += Points.KNOCK_INTO_WATER
                    say("💦 ${shooter.name} sent ${p.name} for a swim +${Points.KNOCK_INTO_WATER}")
                } else {
                    say("💦 ${p.name} splashed")
                }
            }
            is PhysEvent.Holed -> {
                val p = player(e.id) ?: return
                // Knocked in before taking a shot: that still counts as one stroke.
                if (p.strokes == 0) p.strokes = 1
                p.holed = true
                p.bigShots = 0
                val pts = holePoints(p.strokes, true)
                val what = if (p.strokes == 1) "HOLE IN ONE" else "holed out in ${p.strokes}"
                say("⛳ ${p.name} $what! +$pts")
            }
            is PhysEvent.BoxTouched -> {
                val p = player(e.id) ?: return
                if (!boxes[e.box] || p.inventory.size >= INVENTORY_SIZE) return
                val got = PowerUp.entries.random(rng)
                p.inventory += got
                boxes[e.box] = false
                boxRespawn[e.box] = 2
                say("🎁 ${p.name} got ${got.icon} ${got.label}")
            }
            is PhysEvent.Flung -> victim(e.hazard, e.id, "🌪 tornado got")
            is PhysEvent.Blast -> e.ids.forEach { victim(e.hazard, it, "💣 mine blasted") }
            is PhysEvent.BlockHit -> victim(e.hazard, e.id, "🪨 boulder blocked")
        }
    }

    private fun victim(h: Hazard, id: Int, what: String) {
        if (id == h.ownerId || !h.victims.add(id)) return
        val owner = player(h.ownerId) ?: return
        val pts = if (h.type == HazardType.BLOCK) Points.HIT else Points.HAZARD_VICTIM
        owner.bonus += pts
        say("${owner.name}'s $what ${player(id)?.name} +$pts")
    }

    private fun finishRoll(now: Long) {
        for (b in bodies) {
            b.vel = Vec2.ZERO
            b.settled = true
            b.magnet = false
        }
        val shooter = player(shooterId)
        if (powerRoll) {
            // Back to whoever was aiming; their clock kept running while the balls rolled.
            phase = Phase.AIMING
            if (player(order.getOrNull(currentIdx))?.done != false) advanceTurn(now)
            return
        }
        if (shooter != null && shooter.bigShots > 0 && --shooter.bigShots == 0) {
            body(shooter.id)?.let { b ->
                b.r = BALL_R
                if (b.pos.dist(level.cup) < CUP_R) {
                    b.onCourse = false
                    apply(PhysEvent.Holed(b.id))
                }
            }
        }
        bodies.removeAll { !it.onCourse }
        advanceTurn(now)
    }

    private fun say(text: String) {
        feed.addLast(FeedItem(++feedSeq, text))
        while (feed.size > 6) feed.removeFirst()
    }

    // ---- snapshot -------------------------------------------------------------------------------

    fun snapshot(now: Long): MatchState = MatchState(
        phase = phase,
        hostName = hostName,
        levelIndex = levelIndex,
        holeCount = Levels.all.size,
        players = players.map { p ->
            val b = body(p.id)
            PlayerState(
                id = p.id, name = p.name, color = p.color, bot = p.bot, connected = p.connected, total = p.total,
                strokes = p.strokes, holed = p.holed, bonus = p.bonus, holeScores = p.holeScores.toList(),
                inventory = p.inventory.toList(), bigShots = p.bigShots, forceUp = p.forceUp, magnet = p.magnet,
                ball = b?.let { BallState(it.pos.x, it.pos.y, it.r) },
            )
        },
        order = order,
        currentId = if (phase == Phase.AIMING || phase == Phase.ROLLING) order.getOrNull(currentIdx) else null,
        round = round,
        secondsLeft = ((deadline - now) / 1000).toInt().coerceAtLeast(0),
        hazards = hazards.map { HazardState(it.id, it.type, it.pos.x, it.pos.y, it.r, it.ownerId, it.roundsLeft) },
        boxes = boxes.toList(),
        simTime = simTime,
        feed = feed.toList(),
    )

    // ---- test hooks -----------------------------------------------------------------------------

    internal fun giveForTest(id: Int, power: PowerUp) {
        player(id)!!.inventory += power
    }

    internal fun ballOf(id: Int) = body(id)?.pos
}
