package de.powerizzle.powerupgolf.net

import de.powerizzle.powerlink.Link
import de.powerizzle.powerupgolf.game.BotBrain
import de.powerizzle.powerupgolf.game.BotLevel
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
    /** Host only: adds or removes a computer player in the lobby. */
    fun addBot(level: BotLevel)
    fun removeBot(id: Int)
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
    private val brain = BotBrain()
    /** The bot situation we already reacted to, and the bot turn whose power-up choice is made. Loop only. */
    private var botStep: Any? = null
    private var botPowerTurn: Any? = null
    /** Per bot: the other player's turn it already considered ambushing. Loop only. */
    private val ambushTurn = HashMap<Int, Any>()

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
            driveBots(snap)
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

    /** Situation of the bot to move; a new value means it has to think again. */
    private fun botStepOf(s: MatchState): Any? {
        val p = s.player(s.currentId)?.takeIf { s.phase == Phase.AIMING && it.bot != null } ?: return null
        // Power-ups can strike at any moment; whatever changes the course makes the bot look again.
        return listOf(
            s.levelIndex, s.round, p.id, p.strokes, p.inventory, p.forceUp, p.magnet, p.bigShots,
            s.players.map { it.ball }, s.hazards,
        )
    }

    /** Runs on [loop]: lets bots ambush whoever is aiming, and plays the turn of the bot to move. */
    private fun driveBots(s: MatchState) {
        ambushes(s)
        val step = botStepOf(s) ?: return
        if (step == botStep) return
        botStep = step
        val p = s.player(s.currentId)!!
        val level = p.bot!!
        val turn = Triple(s.levelIndex, s.round, p.id)
        val mayUsePower = botPowerTurn != turn
        botPowerTurn = turn
        scope.launch {
            val started = System.currentTimeMillis()
            val intent = brain.decide(s, p.id, level, mayUsePower)
            // Take about as long as a person would, whatever the level: a skilled bot is not a hasty one.
            val pause = if (intent is Intent.UsePower) 1500L + (Math.random() * 1500).toLong() else 4000L + (Math.random() * 5000).toLong()
            // Hurry when the clock runs low, e.g. after someone's ⏳ Countdown.
            while (System.currentTimeMillis() - started < pause && (state.value?.secondsLeft ?: 0) > 2) delay(100)
            withContext(loop) {
                if (botStepOf(match.snapshot(System.currentTimeMillis())) != step) return@withContext
                match.handle(intent, System.currentTimeMillis())
                // Plan the shot afresh: the power-up may have moved balls, or been refused.
                if (intent is Intent.UsePower) botStep = null
            }
        }
    }

    /** Once per turn, each bot holding power-ups may strike at the aiming player a few seconds in. */
    private fun ambushes(s: MatchState) {
        if (s.phase != Phase.AIMING) return
        val turn = Triple(s.levelIndex, s.round, s.currentId)
        for (b in s.players) {
            val level = b.bot ?: continue
            if (b.id == s.currentId || b.inventory.isEmpty() || ambushTurn[b.id] == turn) continue
            ambushTurn[b.id] = turn
            scope.launch(loop) {
                delay(1000L + (Math.random() * 5000).toLong())
                val now = System.currentTimeMillis()
                val snap = match.snapshot(now)
                if (snap.phase != Phase.AIMING || Triple(snap.levelIndex, snap.round, snap.currentId) != turn) return@launch
                brain.ambush(snap, b.id, level)?.let { match.handle(it, now) }
            }
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
    override fun addBot(level: BotLevel) = local(Intent.AddBot(HOST_ID, level))
    override fun removeBot(id: Int) = local(Intent.RemoveBot(HOST_ID, id))
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
    override fun addBot(level: BotLevel) {}
    override fun removeBot(id: Int) {}
    override fun shoot(dx: Double, dy: Double) = send(ClientMsg.Shoot(dx, dy))
    override fun usePower(slot: Int, targetId: Int?, x: Double?, y: Double?) = send(ClientMsg.UsePower(slot, targetId, x, y))

    override fun close() {
        scope.cancel()
        link.close()
    }
}
