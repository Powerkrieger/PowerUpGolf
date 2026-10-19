package de.powerizzle.powerupgolf.net

import de.powerizzle.powerupgolf.game.MatchState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import de.powerizzle.powerlink.LinkConfig
import java.util.UUID

/** Bump when messages change incompatibly; the host turns away players with another version. */
const val PROTOCOL_VERSION = 3

/** How PowerLink finds and connects PowerUp Golf phones. Never change these, old and new versions must agree. */
val LINK_CONFIG = LinkConfig(
    app = "powerupgolf",
    tcpPort = 47410,
    discoveryPort = 47411,
    bluetoothUuid = UUID.fromString("9f1c2e4a-7b3d-4c8e-a5f6-2d0b8e9c1a37"),
)

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
