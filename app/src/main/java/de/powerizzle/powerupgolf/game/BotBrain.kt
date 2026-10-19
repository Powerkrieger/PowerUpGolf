package de.powerizzle.powerupgolf.game

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How well a computer player plays. [angles] × [powers] is the grid of shots it tries, [aimError] (radians)
 * and [powerError] (fraction) how much its actual shot strays from the planned one.
 */
@Serializable
enum class BotLevel(
    val label: String,
    val names: List<String>,
    val angles: Int,
    val powers: Int,
    val aimError: Double,
    val powerError: Double,
    /** Chance to use a power-up on its own turn when it holds one. */
    val powerChance: Double,
    /** Chance to strike at someone else while they aim, once per turn of theirs. */
    val ambushChance: Double,
) {
    EASY("Easy", listOf("Chip", "Divot", "Shank"), 36, 8, 0.10, 0.14, 0.35, 0.15),
    MEDIUM("Medium", listOf("Birdie", "Caddie", "Fairway"), 60, 10, 0.035, 0.05, 0.7, 0.3),
    HARD("Hard", listOf("Eagle", "Albatross", "Ace"), 60, 12, 0.008, 0.015, 1.0, 0.45),
}

/** Decides what a computer player does on its turn. Pure game logic: give it a state, get back an [Intent]. */
class BotBrain(private val rng: Random = Random) {
    private val bots = HashMap<Int, Bot>()

    private fun botFor(levelIndex: Int): Bot = synchronized(bots) { bots.getOrPut(levelIndex) { Bot(Levels.all[levelIndex]) } }

    /**
     * The next move of player [id] in [s]: a power-up when [mayUsePower] and it wants one, otherwise a shot.
     * Takes up to a second or so on the hardest level, so call it off the game loop.
     */
    fun decide(s: MatchState, id: Int, level: BotLevel, mayUsePower: Boolean): Intent {
        val me = s.player(id)!!
        if (mayUsePower && me.inventory.isNotEmpty() && rng.nextDouble() < level.powerChance) {
            choosePower(s, me, level)?.let { return it }
        }
        return shot(s, me, level)
    }

    /**
     * Whether bot [id] wants to sabotage the player who is aiming right now, and how. Cheap, so it may run
     * on the game loop. Null to let them be.
     */
    fun ambush(s: MatchState, id: Int, level: BotLevel): Intent.UsePower? {
        val me = s.player(id) ?: return null
        val aimer = s.player(s.currentId)?.takeIf { it.id != id && it.ball != null } ?: return null
        if (me.inventory.isEmpty() || rng.nextDouble() >= level.ambushChance) return null
        // Mid-shot sabotage only pays against someone who could hole out soon; a hard bot checks that.
        if (level == BotLevel.HARD && botFor(s.levelIndex).walkingDistance(Vec2(aimer.ball!!.x, aimer.ball.y)) > 70) return null
        return choosePower(s, me, level, aimer)
    }

    // ---- shooting -------------------------------------------------------------------------------

    private fun shot(s: MatchState, me: PlayerState, level: BotLevel): Intent.Shoot {
        val ball = me.ball ?: return Intent.Shoot(me.id, 0.0, -0.2)
        val bot = botFor(s.levelIndex)
        val from = Vec2(ball.x, ball.y)
        // An easy bot plays as if alone on the course and forgets about its own power-up effects.
        val world = if (level == BotLevel.EASY) Bot.World(radius = ball.r) else Bot.World(
            others = s.players.mapNotNull { p -> p.ball.takeIf { p.id != me.id } },
            hazards = s.hazards,
            speedFactor = if (me.forceUp) FORCE_UP_FACTOR else 1.0,
            magnet = me.magnet,
            radius = ball.r,
        )
        var all = bot.candidates(from, s.simTime, world, level.angles, level.powers)
        // Then looks closer around the most promising lines.
        if (level == BotLevel.HARD) all = bot.refine(from, s.simTime, world, all.take(5))
        val plan = when (level) {
            // Doesn't always see the best line: any shot nearly as good will do.
            BotLevel.EASY -> all.takeWhile { it.score <= all.first().score + 12 }.random(rng).shot
            BotLevel.MEDIUM -> all.first().shot
            // Prefers shots that still work when the aim is a bit off.
            BotLevel.HARD -> all.take(8).minBy { robustness(bot, from, it, s.simTime, world) }.shot
        }
        val angle = atan2(plan.y, plan.x) + gauss() * level.aimError
        val power = (plan.len() * (1 + gauss() * level.powerError)).coerceIn(0.05, 1.0)
        return Intent.Shoot(me.id, cos(angle) * power, sin(angle) * power)
    }

    /** Average score of [r]'s shot and slightly mis-hit versions of it; lower is better. */
    private fun robustness(bot: Bot, from: Vec2, r: Bot.Result, time: Double, world: Bot.World): Double {
        val a = atan2(r.shot.y, r.shot.x)
        val p = r.shot.len()
        val tries = listOf(0.0 to 1.0, 0.02 to 1.0, -0.02 to 1.0, 0.0 to 1.04, 0.0 to 0.96)
        return tries.sumOf { (da, dp) ->
            val shot = Vec2(cos(a + da), sin(a + da)) * (p * dp).coerceAtMost(1.0)
            val score = if (da == 0.0 && dp == 1.0) r.score else bot.simulate(from, shot, time, world).score
            if (score == Int.MAX_VALUE) 500.0 else score.toDouble()
        } / tries.size
    }

    /** Standard normal sample. */
    private fun gauss(): Double {
        val u = rng.nextDouble().coerceAtLeast(1e-9)
        return sqrt(-2 * ln(u)) * cos(2 * PI * rng.nextDouble())
    }

    // ---- power-ups ------------------------------------------------------------------------------

    private fun choosePower(s: MatchState, me: PlayerState, level: BotLevel, target: PlayerState? = null): Intent.UsePower? {
        val bot = botFor(s.levelIndex)
        val cup = s.level.cup
        fun away(p: PlayerState) = p.ball?.let { bot.walkingDistance(Vec2(it.x, it.y)) } ?: Int.MAX_VALUE
        val rivals = s.players.filter { it.id != me.id && !it.done && it.ball != null }
        // Who to pick on: anyone for an easy bot, the overall leader for a medium one, and for a hard one
        // whoever is closest to holing out right now.
        val victim = target ?: when (level) {
            BotLevel.EASY -> rivals.randomOrNull(rng)
            BotLevel.MEDIUM -> rivals.maxByOrNull { it.total + it.bonus }
            BotLevel.HARD -> rivals.minByOrNull { away(it) }
        }
        val mine = away(me)
        val smart = level != BotLevel.EASY
        for ((slot, power) in me.inventory.withIndex().shuffled(rng)) {
            // Holding back what only helps its own next shot when striking at someone else.
            if (target != null && power.targeting == Targeting.SELF) continue
            val use: Intent.UsePower? = when (power.targeting) {
                Targeting.SELF -> Intent.UsePower(me.id, slot, null, null, null).takeIf { !smart || mine > s.level.funnel * 2 }
                // Only worth it against someone mid-aim, never on its own turn.
                Targeting.AIMER -> Intent.UsePower(me.id, slot, null, null, null).takeIf { target != null && s.currentId == target.id }
                Targeting.ALL_OPPONENTS -> Intent.UsePower(me.id, slot, null, null, null).takeIf { rivals.isNotEmpty() }
                Targeting.OPPONENT -> victim?.let { v ->
                    val worthIt = !smart || when (power) {
                        PowerUp.SWAP -> me.ball != null && away(v) + 10 < mine
                        // A random spot is never near the cup, so warping only hurts someone close to it.
                        PowerUp.PORTAL -> away(v) < 45
                        else -> true
                    }
                    Intent.UsePower(me.id, slot, v.id, null, null).takeIf { worthIt }
                }
                Targeting.PLACE -> victim?.ball?.let { b -> placeSpot(s, me, power, Vec2(b.x, b.y), cup) }
                    ?.let { Intent.UsePower(me.id, slot, null, it.x, it.y) }
            }
            if (use != null) return use
        }
        return null
    }

    /** Where to drop a placed power-up to bother a rival whose ball lies at [target]; null if nowhere sensible. */
    private fun placeSpot(s: MatchState, me: PlayerState, power: PowerUp, target: Vec2, cup: Vec2): Vec2? {
        val level = s.level
        val myBall = me.ball?.let { Vec2(it.x, it.y) }
        val spot = when (power) {
            // A boulder can't land on a ball, so it goes in front of the cup on the rival's side.
            PowerUp.BLOCK -> cup + (target - cup).norm() * 10.0
            // A tornado on the ball flings it right away; mines and ice wait for the next shot.
            else -> target
        }
        if (!level.inside(spot) || level.terrainAt(spot) == Terrain.WATER) return null
        val reach = when (power) {
            PowerUp.TORNADO -> 6.0
            PowerUp.MINE -> MINE_BLAST_R
            PowerUp.BLOCK -> 4.5
            else -> 0.0
        }
        if (myBall != null && myBall.dist(spot) < reach + BALL_R + 2) return null
        if (power == PowerUp.BLOCK && s.players.any { p -> p.ball?.let { Vec2(it.x, it.y).dist(spot) < 4.5 + it.r + 0.5 } == true }) return null
        return spot
    }

    companion object {
        /** A name for a new bot that nobody in [taken] uses yet. */
        fun nameFor(level: BotLevel, taken: Collection<String>): String {
            val names = level.names.map { "🤖 $it" }
            return names.firstOrNull { it !in taken } ?: "${names.first()} ${taken.size + 1}"
        }
    }
}
