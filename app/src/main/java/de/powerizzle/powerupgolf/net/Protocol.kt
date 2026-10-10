package de.powerizzle.powerupgolf.net

import de.powerizzle.powerupgolf.game.MatchState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bump when messages change incompatibly; the host turns away players with another version. */
const val PROTOCOL_VERSION = 1
const val GAME_PORT = 47410

/** Player phone → host. Each message is one line of JSON. */
@Serializable
sealed interface ClientMsg {
    @Serializable @SerialName("hello")
    data class Hello(val name: String, val color: Long, val version: Int) : ClientMsg

    @Serializable @SerialName("shoot")
    data class Shoot(val dx: Double, val dy: Double) : ClientMsg

    @Serializable @SerialName("power")
    data class UsePower(val slot: Int, val targetId: Int? = null, val x: Double? = null, val y: Double? = null) : ClientMsg
}

/** Host → player phones. */
@Serializable
sealed interface HostMsg {
    @Serializable @SerialName("welcome")
    data class Welcome(val playerId: Int) : HostMsg

    @Serializable @SerialName("rejected")
    data class Rejected(val reason: String) : HostMsg

    @Serializable @SerialName("state")
    data class State(val state: MatchState) : HostMsg
}

val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
