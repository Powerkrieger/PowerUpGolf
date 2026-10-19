package de.powerizzle.powerupgolf.game

import kotlinx.serialization.Serializable

/** What a ⏳ Countdown leaves the aiming player. */
const val COUNTDOWN_SECONDS = 5

/** How a power-up is aimed when it is used. */
enum class Targeting { OPPONENT, PLACE, SELF, ALL_OPPONENTS, /** Whoever is aiming right now. */ AIMER }

@Serializable
enum class PowerUp(val label: String, val icon: String, val targeting: Targeting, val help: String) {
    TORNADO("Tornado", "🌪", Targeting.PLACE, "Place a twister that flings away every ball that comes close"),
    PORTAL("Portal", "🌀", Targeting.OPPONENT, "Warp an opponent's ball to a random spot"),
    ENLARGE("Enlarge", "🎈", Targeting.OPPONENT, "Opponent's ball grows too big for the cup for 2 shots"),
    FORCE_UP("Force Up", "💪", Targeting.OPPONENT, "Opponent's next shot is way too strong"),
    ICE("Ice Patch", "🧊", Targeting.PLACE, "Freeze an area so balls glide through it"),
    MINE("Mine", "💣", Targeting.PLACE, "Hidden-ish mine that blasts nearby balls away"),
    BLOCK("Boulder", "🪨", Targeting.PLACE, "Drop a boulder in someone's way"),
    QUAKE("Earthquake", "🌋", Targeting.ALL_OPPONENTS, "Shake every opponent's ball"),
    SWAP("Swap", "🔄", Targeting.OPPONENT, "Trade places with an opponent's ball"),
    MAGNET("Magnet", "🧲", Targeting.SELF, "The cup pulls your next shot in from much farther away"),
    COUNTDOWN("Countdown", "⏳", Targeting.AIMER, "Whoever is aiming has $COUNTDOWN_SECONDS seconds left to shoot, or loses the turn"),
}

@Serializable
enum class HazardType { TORNADO, ICE, MINE, BLOCK }
