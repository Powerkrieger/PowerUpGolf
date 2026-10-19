package de.powerizzle.powerupgolf.game

import kotlinx.serialization.Serializable

@Serializable
enum class Phase { LOBBY, AIMING, ROLLING, HOLE_DONE, GAME_OVER }

@Serializable
data class BallState(val x: Double, val y: Double, val r: Double)

@Serializable
data class PlayerState(
    val id: Int,
    val name: String,
    val color: Long,
    /** Set for computer players. */
    val bot: BotLevel? = null,
    val connected: Boolean = true,
    /** Total over all finished holes, bonus included. */
    val total: Int = 0,
    val strokes: Int = 0,
    val holed: Boolean = false,
    /** Extra points earned on the current hole by hitting and sabotaging. */
    val bonus: Int = 0,
    /** Score of each finished hole (hole points + bonus). The last one decides the playing order. */
    val holeScores: List<Int> = emptyList(),
    val inventory: List<PowerUp> = emptyList(),
    /** Shots left with an enlarged ball. */
    val bigShots: Int = 0,
    val forceUp: Boolean = false,
    val magnet: Boolean = false,
    /** Null when the ball isn't on the course (holed, or the player left). */
    val ball: BallState? = null,
) {
    val done get() = holed || strokes >= MAX_STROKES || !connected
}

@Serializable
data class HazardState(
    val id: Int,
    val type: HazardType,
    val x: Double,
    val y: Double,
    val r: Double,
    val ownerId: Int,
    val roundsLeft: Int,
)

@Serializable
data class FeedItem(val seq: Int, val text: String)

/** Everything a phone needs to draw the game. The host owns it and sends a copy to every player. */
@Serializable
data class MatchState(
    val phase: Phase = Phase.LOBBY,
    val hostName: String = "",
    val levelIndex: Int = 0,
    val holeCount: Int = Levels.all.size,
    val players: List<PlayerState> = emptyList(),
    /** Player ids in playing order for this hole. */
    val order: List<Int> = emptyList(),
    val currentId: Int? = null,
    val round: Int = 0,
    val secondsLeft: Int = 0,
    val hazards: List<HazardState> = emptyList(),
    val boxes: List<Boolean> = emptyList(),
    val simTime: Double = 0.0,
    val feed: List<FeedItem> = emptyList(),
) {
    fun player(id: Int?) = players.firstOrNull { it.id == id }
    val level get() = Levels.all[levelIndex]
}

const val MAX_STROKES = 5
const val MAX_PLAYERS = 6
const val INVENTORY_SIZE = 2

/** Points for holing out after n strokes: 500 for a hole in one, 100 less per extra stroke, 0 if not holed. */
fun holePoints(strokes: Int, holed: Boolean) = if (holed) (600 - 100 * strokes).coerceAtLeast(0) else 0

object Points {
    const val HIT = 25
    const val KNOCK_INTO_WATER = 50
    const val SABOTAGE = 30
    const val HAZARD_VICTIM = 40
}
