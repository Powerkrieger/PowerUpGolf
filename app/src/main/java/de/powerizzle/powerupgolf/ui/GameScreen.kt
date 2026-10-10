package de.powerizzle.powerupgolf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import de.powerizzle.powerupgolf.game.BallState
import de.powerizzle.powerupgolf.game.INVENTORY_SIZE
import de.powerizzle.powerupgolf.game.MAX_STROKES
import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.Phase
import de.powerizzle.powerupgolf.game.PlayerState
import de.powerizzle.powerupgolf.game.Targeting
import de.powerizzle.powerupgolf.game.Vec2
import de.powerizzle.powerupgolf.net.Session

/**
 * Keeps the previous and current state so balls glide smoothly between network updates
 * (the host sends ~30 updates per second while balls roll).
 */
private class Smoother {
    var prev: MatchState? = null
    var cur: MatchState? = null
    var prevAt = 0L
    var curAt = 0L

    fun push(s: MatchState, at: Long) {
        if (s === cur) return
        prev = cur
        prevAt = curAt
        cur = s
        curAt = at
    }

    private fun alpha(now: Long): Double {
        val span = (curAt - prevAt).coerceIn(16_000_000L, 80_000_000L)
        return ((now - curAt).toDouble() / span).coerceIn(0.0, 1.0)
    }

    fun balls(now: Long): Map<Int, BallState> {
        val c = cur ?: return emptyMap()
        val p = prev
        val a = alpha(now)
        return c.players.mapNotNull { pl ->
            val b = pl.ball ?: return@mapNotNull null
            val old = p?.player(pl.id)?.ball
            val shown = if (old == null || c.levelIndex != p.levelIndex || Vec2(old.x - b.x, old.y - b.y).len() > 12) b
            else BallState(old.x + (b.x - old.x) * a, old.y + (b.y - old.y) * a, old.r + (b.r - old.r) * a)
            pl.id to shown
        }.toMap()
    }

    fun simTime(now: Long): Double {
        val c = cur ?: return 0.0
        val p = prev ?: return c.simTime
        if (p.simTime > c.simTime) return c.simTime
        return p.simTime + (c.simTime - p.simTime) * alpha(now)
    }
}

@Composable
fun GameScreen(s: MatchState, myId: Int, session: Session, onLeave: () -> Unit) {
    val me = s.player(myId)
    val myTurn = s.phase == Phase.AIMING && s.currentId == myId
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf(Offset.Zero) }
    var showRules by remember { mutableStateOf(false) }
    val smoother = remember { Smoother() }
    smoother.push(s, System.nanoTime())

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(myTurn) {
        if (myTurn) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(myTurn, s.usedPowerThisTurn) {
        selectedSlot = null
        dragStart = null
    }

    // Redraw every frame for animation and smoothing.
    val frame by produceState(0L) {
        while (true) withFrameNanos { value = it }
    }
    val tm = rememberTextMeasurer()
    val current by rememberUpdatedState(s)

    Column(
        Modifier
            .fillMaxSize()
            .background(DeepGreen)
            .safeDrawingPadding()
    ) {
        TopBar(s, myId, myTurn, onRules = { showRules = true }, onLeave = onLeave)
        Scoreboard(s)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            var view by remember { mutableStateOf<CourseView?>(null) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(myTurn, selectedSlot) {
                        val slot = selectedSlot
                        if (myTurn && slot != null) {
                            detectTapGestures { off ->
                                val st = current
                                val power = st.player(myId)?.inventory?.getOrNull(slot) ?: return@detectTapGestures
                                val w = view?.world(off) ?: return@detectTapGestures
                                when (power.targeting) {
                                    Targeting.OPPONENT -> {
                                        val victim = st.players
                                            .filter { it.id != myId && it.ball != null && !it.done }
                                            .minByOrNull { Vec2(it.ball!!.x, it.ball.y).dist(w) }
                                        if (victim != null && Vec2(victim.ball!!.x, victim.ball.y).dist(w) < 8) {
                                            session.usePower(slot, victim.id, null, null)
                                        }
                                    }
                                    Targeting.PLACE -> session.usePower(slot, null, w.x, w.y)
                                    else -> {}
                                }
                                selectedSlot = null
                            }
                        } else if (myTurn) {
                            detectDragGestures(
                                onDragStart = { dragStart = it; dragNow = it },
                                onDrag = { change, _ -> dragNow = change.position },
                                onDragCancel = { dragStart = null },
                                onDragEnd = {
                                    val start = dragStart
                                    dragStart = null
                                    if (start != null) {
                                        val shot = shotFrom(start, dragNow, size.width.toFloat())
                                        if (shot != null) session.shoot(shot.x, shot.y)
                                    }
                                },
                            )
                        }
                    }
            ) {
                frame // read so the canvas redraws each frame
                val v = CourseView.fit(size, s.level).also { view = it }
                val now = System.nanoTime()
                val balls = smoother.balls(now)
                drawCourse(v, s, balls, smoother.simTime(now), (now / 1_000_000L % 1_000_000L) / 1000f, tm)
                // Aim line
                val start = dragStart
                val myBall = balls[myId]
                if (start != null && myBall != null && myTurn) {
                    val shot = shotFrom(start, dragNow, size.width)
                    if (shot != null) {
                        val from = v.p(myBall.x, myBall.y)
                        val power = shot.len()
                        val reach = shot * (45.0 * if (me?.forceUp == true) 1.8 else 1.0)
                        val col = lerp(Color.White, Color(0xFFFF1744), power.toFloat())
                        drawLine(
                            col, from, from + Offset(v.d(reach.x), v.d(reach.y)), v.d(0.7), StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(v.d(1.5), v.d(1.2))),
                        )
                        drawCircle(col, v.d(1.0), from + Offset(v.d(reach.x), v.d(reach.y)))
                        // Power meter along the bottom
                        drawRect(Color(0x66000000), Offset(0f, size.height - 18f), androidx.compose.ui.geometry.Size(size.width, 18f))
                        drawRect(col, Offset(0f, size.height - 18f), androidx.compose.ui.geometry.Size(size.width * power.toFloat(), 18f))
                    }
                }
            }
            if (s.phase == Phase.HOLE_DONE) {
                HoleResults(s, Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth())
            }
        }
        BottomBar(s, me, myTurn, selectedSlot, onSlot = { i ->
            val power = me?.inventory?.getOrNull(i) ?: return@BottomBar
            when (power.targeting) {
                Targeting.SELF, Targeting.ALL_OPPONENTS -> session.usePower(i, null, null, null)
                else -> selectedSlot = if (selectedSlot == i) null else i
            }
        })
    }
    if (showRules) RulesDialog { showRules = false }
}

/** Slingshot: pulling back from [start] to [now] shoots the other way. Returns direction × power (0..1), or null if too short. */
private fun shotFrom(start: Offset, now: Offset, width: Float): Vec2? {
    val pull = start - now
    val len = pull.getDistance()
    val power = (len / (width * 0.45f)).coerceAtMost(1f)
    if (power < 0.04f) return null
    return Vec2((pull.x / len * power).toDouble(), (pull.y / len * power).toDouble())
}

@Composable
private fun TopBar(s: MatchState, myId: Int, myTurn: Boolean, onRules: () -> Unit, onLeave: () -> Unit) {
    val current = s.player(s.currentId)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Hole ${s.levelIndex + 1}/${s.holeCount} · ${s.level.name}",
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
            )
            val banner = when {
                s.phase == Phase.HOLE_DONE -> "Hole finished!"
                s.phase == Phase.ROLLING -> "Rolling…"
                myTurn -> "Your turn! · ${s.secondsLeft}s"
                current != null -> "${current.name} is aiming… ${s.secondsLeft}s"
                else -> ""
            }
            Text(
                banner, color = if (myTurn) Gold else Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRules) { Text("?", color = Color.White, fontSize = 20.sp) }
        TextButton(onClick = onLeave) { Text("✕", color = Color.White, fontSize = 18.sp) }
    }
}

@Composable
private fun Scoreboard(s: MatchState) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        s.order.mapNotNull { s.player(it) }.forEach { p -> PlayerChip(p, p.id == s.currentId) }
    }
}

@Composable
private fun PlayerChip(p: PlayerState, active: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (active) 0.95f else 0.75f))
            .border(if (active) 2.dp else 0.dp, Gold, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerDot(p, 14)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                p.name + if (!p.connected) " (left)" else "",
                fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
            )
            val strokes = when {
                p.holed -> "⛳ ${p.strokes}"
                else -> "●".repeat(p.strokes) + "○".repeat((MAX_STROKES - p.strokes).coerceAtLeast(0))
            }
            val bonus = if (p.bonus > 0) " +${p.bonus}" else ""
            val fx = (if (p.bigShots > 0) "🎈" else "") + (if (p.forceUp) "💪" else "") + (if (p.magnet) "🧲" else "")
            Text("${p.total}$bonus  $strokes $fx", fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BottomBar(s: MatchState, me: PlayerState?, myTurn: Boolean, selectedSlot: Int?, onSlot: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF143A1A))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Latest happenings, newest first.
        s.feed.takeLast(2).asReversed().forEachIndexed { i, f ->
            Text(f.text, color = Color.White.copy(alpha = if (i == 0) 1f else 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val hint = when {
            selectedSlot != null -> me?.inventory?.getOrNull(selectedSlot)?.let {
                if (it.targeting == Targeting.OPPONENT) "Tap an opponent's ball for ${it.icon} ${it.label}" else "Tap the course to place ${it.icon} ${it.label}"
            }
            myTurn && s.usedPowerThisTurn -> "Power-up used. Now take your shot!"
            myTurn && me?.inventory?.isNotEmpty() == true -> "Use a power-up first, or drag back anywhere to shoot"
            myTurn -> "Drag back anywhere and let go to shoot"
            else -> null
        }
        hint?.let { Text(it, color = Gold, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until INVENTORY_SIZE) {
                val p = me?.inventory?.getOrNull(i)
                val usable = p != null && myTurn && !s.usedPowerThisTurn
                Row(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                selectedSlot == i -> Gold
                                p != null -> Color.White.copy(alpha = if (usable) 0.95f else 0.5f)
                                else -> Color.White.copy(alpha = 0.12f)
                            }
                        )
                        .clickable(enabled = usable) { onSlot(i) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (p == null) {
                        Text("empty slot", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                    } else {
                        Text(p.icon, fontSize = 24.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(p.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(p.help, fontSize = 9.sp, maxLines = 2, lineHeight = 10.sp, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
