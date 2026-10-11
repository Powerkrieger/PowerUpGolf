package de.powerizzle.powerupgolf.net

import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.PLAYER_COLORS
import de.powerizzle.powerupgolf.game.Phase
import de.powerizzle.powerlink.Lan
import de.powerizzle.powerlink.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real TCP on localhost through PowerLink's LAN transport, exactly as phones on one Wi-Fi use it. */
class NetworkTest {
    private class Hosting(val host: GameHost, val server: Job, val port: Int)

    private fun CoroutineScope.hosting(name: String, port: Int): Hosting {
        val guests = Channel<Link>(Channel.UNLIMITED)
        val server = launch { Lan.serve(port, guests) }
        return Hosting(GameHost(name, PLAYER_COLORS[0], guests.receiveAsFlow(), onClose = { server.cancel() }), server, port)
    }

    private suspend fun join(port: Int, name: String, color: Long): GameClient {
        // The server coroutine may need a moment to bind.
        repeat(50) {
            runCatching { return GameClient(Lan.connect("127.0.0.1", port), name, color) }
            delay(100)
        }
        error("server didn't come up")
    }

    private suspend fun StateFlow<MatchState?>.await(what: (MatchState) -> Boolean) =
        withTimeout(30_000) { first { it != null && what(it) }!! }

    @Test
    fun twoPhonesPlayOverTcp(): Unit = runBlocking {
        val port = 47500 + (Math.random() * 400).toInt()
        val host = hosting("Host", port).host
        val client = join(port, "Guest", PLAYER_COLORS[0])
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
        val a = hosting("A", port).host
        val c = join(port, "x", 0)
        try {
            c.state.await { it.players.size == 2 }
            a.startGame()
            a.state.await { it.phase == Phase.AIMING }
            val late = join(port, "late", 0)
            withTimeout(30_000) { late.problem.first { it != null } }
            late.close()
        } finally {
            c.close()
            a.close()
        }
    }
}
