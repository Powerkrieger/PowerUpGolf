package de.powerizzle.powerupgolf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.powerizzle.powerupgolf.game.MAX_PLAYERS
import de.powerizzle.powerupgolf.game.MAX_STROKES
import de.powerizzle.powerupgolf.game.PLAYER_COLORS
import de.powerizzle.powerupgolf.game.Phase
import de.powerizzle.powerupgolf.game.PowerUp
import de.powerizzle.powerlink.ui.HostPanel
import de.powerizzle.powerlink.ui.JoinPanel
import de.powerizzle.powerupgolf.net.Session

@Composable
fun PowerUpGolfApp(vm: AppViewModel = viewModel()) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF8BD17C), Color(0xFF3E9B4F))))
    ) {
        when (vm.screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.JOIN -> JoinScreen(vm)
            Screen.GAME -> vm.session?.let { session ->
                val host = vm.linkHost
                GameRoot(session, onLeave = vm::backToMenu, hostPanel = host?.let { h -> { HostPanel(vm.powerLink, h, Modifier.fillMaxWidth()) } })
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel) {
    var showRules by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("⛳⚡", fontSize = 56.sp)
        Text("PowerUp Golf", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("Party mini golf with dirty tricks", color = Color.White.copy(alpha = 0.9f))
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = vm::updateName,
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Ball colour", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PLAYER_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (c == vm.color) 3.dp else 0.dp, Color.Black, CircleShape)
                                .clickable { vm.updateColor(c) }
                        )
                    }
                }
            }
        }
        Button(onClick = vm::host, enabled = vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Host a game", fontSize = 18.sp)
        }
        Button(onClick = vm::openJoin, enabled = vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Join a game", fontSize = 18.sp)
        }
        Button(onClick = vm::playBots, enabled = vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Play vs bots 🤖", fontSize = 18.sp)
        }
        Text(
            "Play over the same Wi-Fi, a phone's hotspot, Bluetooth or Wi-Fi Direct, or alone against bots.",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { showRules = true }) { Text("How to play", color = Color.White) }
    }
    if (showRules) RulesDialog { showRules = false }
}

@Composable
fun RulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How to play") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• Everyone plays the same hole at the same time. Take turns, one stroke each.")
                Text("• Shoot with the pad at the bottom: pull away from the ball like a slingshot and let go. The farther out, the harder. Let go on the ball to cancel.")
                Text("• The host can add 🤖 bots (easy, medium, hard) in the lobby, also to a game with friends.")
                Text("• You have $MAX_STROKES strokes per hole. The best scorer of the last hole goes first.")
                Text("• Hole in one: 500. Two strokes: 400, three: 300 … not holed: 0.")
                Text("• Bump other balls: +25. Knock someone into the water: +50. Sabotage with power-ups: +30 or more.")
                Text("• Roll over 🎁 boxes to collect power-ups (max 2). Fire them whenever you like, even on someone else's turn or while balls roll:")
                PowerUp.entries.forEach { Text("   ${it.icon} ${it.label}: ${it.help}", fontSize = 13.sp) }
                Text("• The cup sits in a dip: slow balls nearby roll in on their own.")
                Text("• Portals come in pairs: roll into one, come out of the other at the same speed.")
                Text("• Saw blades slide back and forth, orange pads speed you up in the arrow's direction.")
            }
        },
    )
}

@Composable
private fun JoinScreen(vm: AppViewModel) {
    BackHandler(onBack = vm::backToMenu)
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Join a game", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
        Card(Modifier.fillMaxWidth()) {
            JoinPanel(vm.powerLink, onJoined = { link, _ -> vm.joined(link) }, Modifier.padding(16.dp))
        }
        OutlinedButton(onClick = vm::backToMenu, modifier = Modifier.fillMaxWidth()) { Text("Back", color = Color.White) }
    }
}

/** Everything after hosting/joining: lobby, the course, the final ranking. */
@Composable
private fun GameRoot(session: Session, onLeave: () -> Unit, hostPanel: (@Composable () -> Unit)?) {
    val state by session.state.collectAsStateWithLifecycle()
    val myId by session.myId.collectAsStateWithLifecycle()
    val problem by session.problem.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }

    val s = state
    when {
        s == null || myId < 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (problem == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Connecting…", color = Color.White)
            }
        }
        s.phase == Phase.LOBBY -> LobbyScreen(s, session, onLeave = { confirmLeave = true }, hostPanel = hostPanel)
        s.phase == Phase.GAME_OVER -> FinalScreen(s, myId, onLeave)
        else -> GameScreen(s, myId, session, onLeave = { confirmLeave = true })
    }

    problem?.let {
        AlertDialog(
            onDismissRequest = onLeave,
            confirmButton = { TextButton(onClick = onLeave) { Text("Back to menu") } },
            title = { Text("Game over") },
            text = { Text(it) },
        )
    }
    if (confirmLeave && problem == null) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            confirmButton = { TextButton(onClick = onLeave) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
            title = { Text("Leave the game?") },
            text = { Text(if (session.isHost) "You're the host: the game ends for everyone." else "Your ball will be removed.") },
        )
    }
}
