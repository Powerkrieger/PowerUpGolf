package de.powerizzle.powerupgolf.net

import de.powerizzle.powerupgolf.game.HOST_ID
import de.powerizzle.powerupgolf.game.Intent
import de.powerizzle.powerupgolf.game.Match
import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** One phone's view of a game, whether it hosts it or joined it. The UI only talks to this. */
interface Session {
    val isHost: Boolean
    /** Our player id; -1 until the host has welcomed us. */
    val myId: StateFlow<Int>
    val state: StateFlow<MatchState?>
    /** Set when the connection is gone or we were turned away. */
    val problem: StateFlow<String?>

    fun startGame()
    fun shoot(dx: Double, dy: Double)
    fun usePower(slot: Int, targetId: Int?, x: Double?, y: Double?)
    fun close()
}

/**
 * Hosting: runs the authoritative [Match], accepts players over TCP and answers discovery broadcasts.
 * All [Match] access happens on [loop], a single-threaded view of the default dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameHost(hostName: String, color: Long, private val port: Int = GAME_PORT, private val discoverable: Boolean = true) : Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loop = Dispatchers.Default.limitedParallelism(1)
    private val match = Match(hostName)
    private val clients = ConcurrentHashMap<Int, Channel<String>>()
    private var server: ServerSocket? = null

    override val isHost = true
    override val myId = MutableStateFlow(HOST_ID)
    override val state = MutableStateFlow<MatchState?>(null)
    override val problem = MutableStateFlow<String?>(null)

    init {
        match.join(hostName, color)
        val now = System.currentTimeMillis()
        state.value = match.snapshot(now)
        scope.launch(loop) { gameLoop() }
        scope.launch { acceptLoop() }
        if (discoverable) scope.launch {
            runCatching {
                Discovery.respond {
                    val s = state.value
                    Discovery.Beacon(hostName, s?.players?.size ?: 0, s?.phase == Phase.LOBBY)
                }
            }
        }
    }

    private suspend fun gameLoop() {
        var frame = 0L
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            match.tick(now)
            val snap = match.snapshot(now)
            if (snap != state.value) {
                state.value = snap
                // While balls roll, every second frame (~30 Hz) is plenty; phones interpolate in between.
                if (snap.phase != Phase.ROLLING || frame % 2 == 0L) {
                    val line = wireJson.encodeToString(HostMsg.serializer(), HostMsg.State(snap))
                    for (c in clients.values) c.trySend(line)
                }
            }
            frame++
            delay(16)
        }
    }

    private suspend fun acceptLoop() {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            problem.value = "Can't open port $port: ${e.message}"
            return
        }
        server = s
        try {
            while (scope.isActive) {
                val socket = cancellableBlocking({ s.close() }) { s.accept() }
                scope.launch { serve(Link(socket)) }
            }
        } catch (_: Exception) {
            // server closed
        } finally {
            s.close()
        }
    }

    private suspend fun serve(link: Link) {
        var id: Int? = null
        try {
            val hello = withContext(Dispatchers.IO) { link.readLine() }
                ?.let { runCatching { wireJson.decodeFromString(ClientMsg.serializer(), it) }.getOrNull() } as? ClientMsg.Hello
                ?: return
            if (hello.version != PROTOCOL_VERSION) {
                reject(link, "Different app version. Update both phones.")
                return
            }
            id = withContext(loop) { match.join(hello.name, hello.color) }
            if (id == null) {
                reject(link, "Game already started or full.")
                return
            }
            val playerId: Int = id
            withContext(Dispatchers.IO) { link.sendLine(wireJson.encodeToString(HostMsg.serializer(), HostMsg.Welcome(playerId))) }
            // Only the newest state matters, so a slow phone simply skips frames.
            val outbox = Channel<String>(Channel.CONFLATED)
            clients[playerId] = outbox
            state.value?.let { outbox.trySend(wireJson.encodeToString(HostMsg.serializer(), HostMsg.State(it))) }
            val writer: Job = scope.launch(Dispatchers.IO) {
                try {
                    for (line in outbox) link.sendLine(line)
                } catch (_: Exception) {
                    link.close()
                }
            }
            try {
                while (true) {
                    val line = cancellableBlocking({ link.close() }) { link.readLine() } ?: break
                    val msg = runCatching { wireJson.decodeFromString(ClientMsg.serializer(), line) }.getOrNull() ?: continue
                    val intent = when (msg) {
                        is ClientMsg.Shoot -> Intent.Shoot(playerId, msg.dx, msg.dy)
                        is ClientMsg.UsePower -> Intent.UsePower(playerId, msg.slot, msg.targetId, msg.x, msg.y)
                        is ClientMsg.Hello -> null
                    } ?: continue
                    act(intent)
                }
            } finally {
                writer.cancel()
            }
        } catch (_: Exception) {
            // connection broke
        } finally {
            link.close()
            id?.let {
                clients.remove(it)?.close()
                act(Intent.Leave(it))
            }
        }
    }

    private suspend fun reject(link: Link, reason: String) = withContext(Dispatchers.IO) {
        runCatching { link.sendLine(wireJson.encodeToString(HostMsg.serializer(), HostMsg.Rejected(reason))) }
    }

    private suspend fun act(intent: Intent) = withContext(loop) {
        match.handle(intent, System.currentTimeMillis())
    }

    private fun local(intent: Intent) {
        scope.launch { act(intent) }
    }

    override fun startGame() = local(Intent.Start(HOST_ID))
    override fun shoot(dx: Double, dy: Double) = local(Intent.Shoot(HOST_ID, dx, dy))
    override fun usePower(slot: Int, targetId: Int?, x: Double?, y: Double?) = local(Intent.UsePower(HOST_ID, slot, targetId, x, y))

    override fun close() {
        scope.cancel()
        runCatching { server?.close() }
        clients.values.forEach { it.close() }
    }
}

/** Joining: forwards our intents to the host and mirrors the state it sends back. */
class GameClient(private val address: String, name: String, color: Long, private val port: Int = GAME_PORT) : Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var link: Link? = null

    override val isHost = false
    override val myId = MutableStateFlow(-1)
    override val state = MutableStateFlow<MatchState?>(null)
    override val problem = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            val socket = Socket()
            try {
                cancellableBlocking({ socket.close() }) { socket.connect(InetSocketAddress(address.trim(), port), 5000) }
                val l = Link(socket)
                link = l
                l.sendLine(wireJson.encodeToString(ClientMsg.serializer(), ClientMsg.Hello(name, color, PROTOCOL_VERSION)))
                while (true) {
                    val line = cancellableBlocking({ l.close() }) { l.readLine() } ?: break
                    when (val msg = runCatching { wireJson.decodeFromString(HostMsg.serializer(), line) }.getOrNull()) {
                        is HostMsg.Welcome -> myId.value = msg.playerId
                        is HostMsg.State -> state.value = msg.state
                        is HostMsg.Rejected -> {
                            problem.value = msg.reason
                            return@launch
                        }
                        null -> {}
                    }
                }
                if (state.value?.phase != Phase.GAME_OVER) problem.value = "The host ended the game."
            } catch (e: Exception) {
                if (isActive) problem.value = "Connection lost: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                socket.close()
            }
        }
    }

    private fun send(msg: ClientMsg) {
        val l = link ?: return
        scope.launch { runCatching { l.sendLine(wireJson.encodeToString(ClientMsg.serializer(), msg)) } }
    }

    override fun startGame() {}
    override fun shoot(dx: Double, dy: Double) = send(ClientMsg.Shoot(dx, dy))
    override fun usePower(slot: Int, targetId: Int?, x: Double?, y: Double?) = send(ClientMsg.UsePower(slot, targetId, x, y))

    override fun close() {
        scope.cancel()
        link?.close()
    }
}
