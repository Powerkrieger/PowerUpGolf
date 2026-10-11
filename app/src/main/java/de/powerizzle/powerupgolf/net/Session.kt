package de.powerizzle.powerupgolf.net

import de.powerizzle.powerlink.Link
import de.powerizzle.powerupgolf.game.HOST_ID
import de.powerizzle.powerupgolf.game.Intent
import de.powerizzle.powerupgolf.game.MAX_PLAYERS
import de.powerizzle.powerupgolf.game.Match
import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Hosting: runs the authoritative [Match] and serves every guest that PowerLink hands over in [guests].
 * [advertise] tells finders how full the game is. All [Match] access happens on [loop], a single-threaded
 * view of the default dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameHost(
    hostName: String,
    color: Long,
    guests: Flow<Link>,
    private val advertise: (info: String, open: Boolean) -> Unit = { _, _ -> },
    private val onClose: () -> Unit = {},
) : Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loop = Dispatchers.Default.limitedParallelism(1)
    private val match = Match(hostName)
    private val clients = ConcurrentHashMap<Int, Channel<String>>()

    override val isHost = true
    override val myId = MutableStateFlow(HOST_ID)
    override val state = MutableStateFlow<MatchState?>(null)
    override val problem = MutableStateFlow<String?>(null)

    init {
        match.join(hostName, color)
        val now = System.currentTimeMillis()
        state.value = match.snapshot(now)
        scope.launch(loop) { gameLoop() }
        scope.launch { guests.collect { link -> scope.launch { serve(link) } } }
    }

    private suspend fun gameLoop() {
        var frame = 0L
        var advertised: Pair<String, Boolean>? = null
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
            val ad = "${snap.players.size}/$MAX_PLAYERS players" to (snap.phase == Phase.LOBBY && snap.players.size < MAX_PLAYERS)
            if (ad != advertised) {
                advertised = ad
                advertise(ad.first, ad.second)
            }
            frame++
            delay(16)
        }
    }

    private suspend fun serve(link: Link) {
        var id: Int? = null
        try {
            val hello = withTimeoutOrNull(10_000) { link.receive() }
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
            link.send(wireJson.encodeToString(HostMsg.serializer(), HostMsg.Welcome(playerId)))
            // Only the newest state matters, so a slow phone simply skips frames.
            val outbox = Channel<String>(Channel.CONFLATED)
            clients[playerId] = outbox
            state.value?.let { outbox.trySend(wireJson.encodeToString(HostMsg.serializer(), HostMsg.State(it))) }
            val writer: Job = scope.launch {
                for (line in outbox) if (!link.send(line)) break
            }
            try {
                while (true) {
                    val line = link.receive() ?: break
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
        } finally {
            link.close()
            id?.let {
                clients.remove(it)?.close()
                withContext(NonCancellable) { act(Intent.Leave(it)) }
            }
        }
    }

    private suspend fun reject(link: Link, reason: String) {
        link.send(wireJson.encodeToString(HostMsg.serializer(), HostMsg.Rejected(reason)))
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
        clients.values.forEach { it.close() }
        onClose()
    }
}

/** Joining: forwards our intents to the host over [link] and mirrors the state it sends back. */
class GameClient(private val link: Link, name: String, color: Long) : Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isHost = false
    override val myId = MutableStateFlow(-1)
    override val state = MutableStateFlow<MatchState?>(null)
    override val problem = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            try {
                if (!link.send(wireJson.encodeToString(ClientMsg.serializer(), ClientMsg.Hello(name, color, PROTOCOL_VERSION)))) {
                    problem.value = "Connection lost."
                    return@launch
                }
                while (true) {
                    val line = link.receive() ?: break
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
                if (state.value?.phase != Phase.GAME_OVER) problem.value = "Lost the connection to the host."
            } finally {
                link.close()
            }
        }
    }

    private fun send(msg: ClientMsg) {
        scope.launch { link.send(wireJson.encodeToString(ClientMsg.serializer(), msg)) }
    }

    override fun startGame() {}
    override fun shoot(dx: Double, dy: Double) = send(ClientMsg.Shoot(dx, dy))
    override fun usePower(slot: Int, targetId: Int?, x: Double?, y: Double?) = send(ClientMsg.UsePower(slot, targetId, x, y))

    override fun close() {
        scope.cancel()
        link.close()
    }
}
