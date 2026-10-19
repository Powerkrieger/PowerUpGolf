package de.powerizzle.powerupgolf.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.powerizzle.powerupgolf.game.BotLevel
import de.powerizzle.powerupgolf.game.Levels
import de.powerizzle.powerupgolf.game.MAX_PLAYERS
import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.PlayerState
import de.powerizzle.powerupgolf.game.holePoints
import de.powerizzle.powerupgolf.net.Session

@Composable
fun PlayerDot(p: PlayerState, size: Int = 18) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(p.color))
    )
}

@Composable
fun LobbyScreen(s: MatchState, session: Session, onLeave: () -> Unit, hostPanel: (@Composable () -> Unit)?) {
    var showRules by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("${s.hostName}'s game", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
        hostPanel?.invoke()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Players ${s.players.size}/$MAX_PLAYERS", style = MaterialTheme.typography.titleMedium)
                s.players.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerDot(p, 24)
                        Spacer(Modifier.width(10.dp))
                        Text(p.name, fontSize = 18.sp)
                        if (p.id == 0) Text("  (host)", color = Color.Gray)
                        p.bot?.let { level ->
                            Text("  ${level.label}", color = Color.Gray, modifier = Modifier.weight(1f))
                            if (session.isHost) TextButton(onClick = { session.removeBot(p.id) }) { Text("✕") }
                        }
                    }
                }
                if (session.isHost && s.players.size < MAX_PLAYERS) {
                    Text("Add a bot", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BotLevel.entries.forEach { level ->
                            OutlinedButton(onClick = { session.addBot(level) }, modifier = Modifier.weight(1f)) { Text(level.label) }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Course: ${Levels.all.size} holes", style = MaterialTheme.typography.titleMedium)
                Levels.all.forEachIndexed { i, l -> Text("${i + 1}. ${l.name}", fontSize = 14.sp) }
            }
        }
        if (session.isHost) {
            Button(onClick = session::startGame, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (s.players.size == 1) "Start (practice alone)" else "Tee off!", fontSize = 18.sp)
            }
        } else {
            Text("Waiting for ${s.hostName} to start…", color = Color.White, fontSize = 18.sp)
        }
        TextButton(onClick = { showRules = true }) { Text("How to play", color = Color.White) }
        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Leave", color = Color.White) }
    }
    if (showRules) RulesDialog { showRules = false }
}

/** Per-hole breakdown, shown between holes. */
@Composable
fun HoleResults(s: MatchState, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hole ${s.levelIndex + 1}: ${s.level.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row {
                Text("", Modifier.weight(1f))
                Header("Hole")
                Header("Bonus")
                Header("Total")
            }
            HorizontalDivider()
            s.players.filter { it.id in s.order }.sortedByDescending { it.total }.forEach { p ->
                val pts = holePoints(p.strokes, p.holed)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerDot(p)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !p.holed -> "not holed"
                                p.strokes == 1 -> "HOLE IN ONE!"
                                else -> "${p.strokes} strokes"
                            },
                            fontSize = 12.sp,
                        )
                    }
                    Cell("$pts")
                    Cell("+${p.bonus}")
                    Cell("${p.total}", bold = true)
                }
            }
            Text("Next hole in ${s.secondsLeft}s", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, Modifier.width(56.dp), fontSize = 12.sp, color = Color.Gray)

@Composable
private fun Cell(t: String, bold: Boolean = false) =
    Text(t, Modifier.width(56.dp), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
fun FinalScreen(s: MatchState, myId: Int, onLeave: () -> Unit) {
    val ranking = s.players.sortedByDescending { it.total }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("🏆", fontSize = 64.sp)
        Text("${ranking.first().name} wins!", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ranking.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf("🥇", "🥈", "🥉").getOrElse(i) { "${i + 1}." }, fontSize = 22.sp, modifier = Modifier.width(40.dp))
                        PlayerDot(p, 22)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (p.id == myId) " (you)" else "", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(p.holeScores.joinToString(" · "), fontSize = 12.sp, color = Color.Gray)
                        }
                        Text("${p.total}", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                }
            }
        }
        Button(onClick = onLeave, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Back to menu") }
    }
}
