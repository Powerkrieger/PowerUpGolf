package de.powerizzle.powerupgolf.net

import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.PLAYER_COLORS
import de.powerizzle.powerupgolf.game.Phase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTest {
    private suspend fun StateFlow<MatchState?>.await(what: (MatchState) -> Boolean) =
        withTimeout(30_000) { first { it != null && what(it) }!! }

    @Test
    fun twoPhonesPlayOverTcp(): Unit = runBlocking {
        val port = 47500 + (Math.random() * 400).toInt()
        val host = GameHost("Host", PLAYER_COLORS[0], port, discoverable = false)
        val client = GameClient("127.0.0.1", "Guest", PLAYER_COLORS[0], port)
        try {
            val lobby = try { client.state.await { it.players.size == 2 } } catch (e: Exception) {
                throw AssertionError("client=${client.state.value} problem=${client.problem.value} host=${host.state.value} hostProblem=${host.problem.value}", e)
            }
            assertEquals(1, client.myId.value)
            assertTrue("colours are unique", lobby.players[0].color != lobby.players[1].color)

            host.startGame()
            client.state.await { it.phase == Phase.AIMING && it.currentId == 0 }
            host.shoot(0.0, -0.15)
            client.state.await { it.currentId == 1 && it.phase == Phase.AIMING }
            client.shoot(0.0, -0.15)
            val s = host.state.await { it.currentId == 0 && it.player(1)!!.strokes == 1 }
            assertEquals(1, s.player(0)!!.strokes)

            client.close()
            host.state.await { !it.player(1)!!.connected }
        } finally {
            client.close()
            host.close()
        }
    }

    @Test
    fun lateJoinerIsTurnedAway(): Unit = runBlocking {
        val port = 47900 + (Math.random() * 50).toInt()
        val a = GameHost("A", PLAYER_COLORS[0], port, discoverable = false)
        val c = GameClient("127.0.0.1", "x", 0, port)
        try {
            c.state.await { it.players.size == 2 }
            a.startGame()
            a.state.await { it.phase == Phase.AIMING }
            val late = GameClient("127.0.0.1", "late", 0, port)
            withTimeout(30_000) { late.problem.first { it != null } }
            late.close()
        } finally {
            c.close()
            a.close()
        }
    }
}
