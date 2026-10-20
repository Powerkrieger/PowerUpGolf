package de.powerizzle.powerupgolf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.foundation.gestures.detectTapGestures
import de.powerizzle.powerupgolf.game.BallState
import de.powerizzle.powerupgolf.game.INVENTORY_SIZE
import de.powerizzle.powerupgolf.game.MAX_STROKES
import de.powerizzle.powerupgolf.game.MatchState
import de.powerizzle.powerupgolf.game.Phase
import de.powerizzle.powerupgolf.game.PlayerState
import de.powerizzle.powerupgolf.game.COUNTDOWN_SECONDS
import de.powerizzle.powerupgolf.game.Targeting
import de.powerizzle.powerupgolf.game.Vec2
import de.powerizzle.powerupgolf.net.Session
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    /** Power-ups work any time balls are on the course, whoever's turn it is. */
    val canPower = (s.phase == Phase.AIMING || s.phase == Phase.ROLLING) && me?.connected == true
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    /** The shot being dialled in on the pad: direction × power (0..1), or null. */
    var aim by remember { mutableStateOf<Vec2?>(null) }
    var showRules by remember { mutableStateOf(false) }
    val smoother = remember { Smoother() }
    /** The last aim shared with the others. */
    val sentAim = remember { arrayOfNulls<Vec2>(1) }
    smoother.push(s, System.nanoTime())

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(myTurn) {
        if (myTurn) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(myTurn) {
        aim = null
    }
    LaunchedEffect(canPower, me?.inventory) {
        selectedSlot = null
    }

    // Redraw every frame for animation and smoothing.
    val frame by produceState(0L) {
        while (true) withFrameNanos { value = it }
    }
    val tm = rememberTextMeasurer()
    val current by rememberUpdatedState(s)

    // Every bar has a fixed height, so the course never gets resized (and re-scaled) while playing.
    Column(
        Modifier
            .fillMaxSize()
            .background(DeepGreen)
            .safeDrawingPadding()
    ) {
        TopBar(s, myTurn, onRules = { showRules = true }, onLeave = onLeave)
        Scoreboard(s)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Plain holder rather than state: it is updated while drawing and must not trigger recomposition.
            val view = remember { arrayOfNulls<CourseView>(1) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(canPower, selectedSlot) {
                        val slot = selectedSlot
                        if (!canPower || slot == null) return@pointerInput
                        detectTapGestures { off ->
                            val st = current
                            val power = st.player(myId)?.inventory?.getOrNull(slot) ?: return@detectTapGestures
                            val w = view[0]?.world(off) ?: return@detectTapGestures
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
                    }
            ) {
                frame // read so the canvas redraws each frame
                val v = CourseView.fit(size, s.level).also { view[0] = it }
                val now = System.nanoTime()
                val balls = smoother.balls(now)
                drawCourse(v, s, balls, smoother.simTime(now), (now / 1_000_000L % 1_000_000L) / 1000f, tm)
                // Our own arrow follows the finger without lag; everyone else's comes from the host.
                val shot = if (myTurn) aim else s.aim?.let { Vec2(it.dx, it.dy) }
                val aimer = balls[s.currentId]
                if (shot != null && aimer != null && s.phase == Phase.AIMING) drawAimArrow(v, aimer, shot)
            }
            FeedToast(s, Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 12.dp, end = 12.dp))
            if (s.phase == Phase.HOLE_DONE) {
                HoleResults(s, Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth())
            }
        }
        BottomPanel(
            s, me, myTurn, canPower, selectedSlot, aim,
            onSlot = { i ->
                val power = me?.inventory?.getOrNull(i) ?: return@BottomPanel
                when (power.targeting) {
                    Targeting.SELF, Targeting.ALL_OPPONENTS, Targeting.AIMER -> session.usePower(i, null, null, null)
                    else -> selectedSlot = if (selectedSlot == i) null else i
                }
            },
            onAim = {
                aim = it
                // Two decimals are plenty for the others' arrow and spare the network most finger jitter.
                val q = it?.let { a -> Vec2((a.x * 100).roundToInt() / 100.0, (a.y * 100).roundToInt() / 100.0) }
                if (q != sentAim[0]) {
                    sentAim[0] = q
                    session.aim(q?.x, q?.y)
                }
            },
            onShoot = { session.shoot(it.x, it.y) },
        )
    }
    if (showRules) RulesDialog { showRules = false }
}

/** Green for a soft putt, through yellow, to red for full power. */
private fun powerColor(power: Float): Color =
    if (power < 0.5f) lerp(Color(0xFF66BB6A), Color(0xFFFFEB3B), power * 2) else lerp(Color(0xFFFFEB3B), Color(0xFFFF1744), (power - 0.5f) * 2)

/** A short arrow next to the ball: its size and colour show the power, its direction the shot. */
private fun DrawScope.drawAimArrow(v: CourseView, ball: BallState, shot: Vec2) {
    val power = shot.len().toFloat()
    val dir = shot.norm()
    val side = dir.perp()
    val start = Vec2(ball.x, ball.y) + dir * (ball.r + 0.8)
    val length = 2.5 + 5.0 * power
    val head = 1.2 + 1.3 * power
    val tip = start + dir * length
    val col = powerColor(power)
    drawLine(Color(0x55000000), v.p(start) + Offset(v.d(0.3), v.d(0.4)), v.p(tip - dir * head) + Offset(v.d(0.3), v.d(0.4)), v.d(0.6 + 0.5 * power), StrokeCap.Round)
    drawLine(col, v.p(start), v.p(tip - dir * head), v.d(0.6 + 0.5 * power), StrokeCap.Round)
    val arrow = Path().apply {
        v.p(tip).let { moveTo(it.x, it.y) }
        v.p(tip - dir * head + side * (head * 0.75)).let { lineTo(it.x, it.y) }
        v.p(tip - dir * head - side * (head * 0.75)).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(arrow, col)
    drawPath(arrow, Color(0x88000000), style = Stroke(v.d(0.2)))
}

/** The newest feed message, shown briefly on top of the course without moving anything. */
@Composable
private fun FeedToast(s: MatchState, modifier: Modifier) {
    val latest = s.feed.lastOrNull()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(latest?.seq) {
        if (latest == null) return@LaunchedEffect
        visible = true
        delay(3500)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            latest?.text ?: "",
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xAA000000))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TopBar(s: MatchState, myTurn: Boolean, onRules: () -> Unit, onLeave: () -> Unit) {
    val current = s.player(s.currentId)
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Hole ${s.levelIndex + 1}/${s.holeCount} · ${s.level.name}",
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val banner = when {
                s.phase == Phase.HOLE_DONE -> "Hole finished!"
                s.phase == Phase.ROLLING -> "Rolling…"
                myTurn -> "Your turn! · ${s.secondsLeft}s"
                current != null -> "${current.name} is aiming… ${s.secondsLeft}s"
                else -> ""
            }
            Text(
                banner, color = if (s.phase == Phase.AIMING && s.secondsLeft <= COUNTDOWN_SECONDS) Color(0xFFFF5252) else if (myTurn) Gold else Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
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
            .height(48.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
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
private fun BottomPanel(
    s: MatchState,
    me: PlayerState?,
    myTurn: Boolean,
    canPower: Boolean,
    selectedSlot: Int?,
    aim: Vec2?,
    onSlot: (Int) -> Unit,
    onAim: (Vec2?) -> Unit,
    onShoot: (Vec2) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PANEL_HEIGHT)
            .background(Color(0xFF143A1A))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val hint = when {
                selectedSlot != null -> me?.inventory?.getOrNull(selectedSlot)?.let {
                    if (it.targeting == Targeting.OPPONENT) "Tap an opponent's ball for ${it.icon} ${it.label}" else "Tap the course to place ${it.icon} ${it.label}"
                }
                aim != null -> "Power ${(aim.len() * 100).roundToInt()}% · let go to shoot, back onto the ball to cancel"
                myTurn -> "Pull away from the ball in the pad. The shot goes the other way."
                canPower && me?.inventory?.isNotEmpty() == true -> "Your power-ups work any time, even now. Strike!"
                else -> "Wait for your turn…"
            }
            Text(
                hint ?: "", Modifier.height(34.dp), color = Gold, fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            for (i in 0 until INVENTORY_SIZE) {
                val p = me?.inventory?.getOrNull(i)
                val usable = p != null && canPower && (p.targeting != Targeting.AIMER || (s.phase == Phase.AIMING && !myTurn))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                selectedSlot == i -> Gold
                                p != null -> Color.White.copy(alpha = if (usable) 0.95f else 0.5f)
                                else -> Color.White.copy(alpha = 0.12f)
                            }
                        )
                        .clickable(enabled = usable) { onSlot(i) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (p == null) {
                        Text("empty slot", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                    } else {
                        Text(p.icon, fontSize = 22.sp)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(p.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                            Text(p.help, fontSize = 9.sp, maxLines = 2, lineHeight = 10.sp, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        ShotPad(
            enabled = myTurn && selectedSlot == null && me?.ball != null,
            ballColor = me?.let { Color(it.color) } ?: Color.White,
            aim = aim,
            onAim = onAim,
            onShoot = onShoot,
            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
        )
    }
}

private val PANEL_HEIGHT = 172.dp
private val PAD_BALL_R = 14.dp

/**
 * The shooting pad: a ball with room around it. Pull away from the ball like a slingshot;
 * the farther out (up to the outer ring), the harder the shot. Releasing on the ball cancels.
 */
@Composable
private fun ShotPad(
    enabled: Boolean,
    ballColor: Color,
    aim: Vec2?,
    onAim: (Vec2?) -> Unit,
    onShoot: (Vec2) -> Unit,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val currentOnAim by rememberUpdatedState(onAim)
    val currentOnShoot by rememberUpdatedState(onShoot)
    Canvas(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0D2A12))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val ballR = PAD_BALL_R.toPx()
                val maxR = size.width / 2f - 6.dp.toPx()
                fun shotAt(pos: Offset): Vec2? {
                    val pull = Offset(size.width / 2f, size.height / 2f) - pos
                    val dist = pull.getDistance()
                    if (dist < ballR * 1.2f) return null
                    val power = (0.04f + 0.96f * (dist - ballR * 1.2f) / (maxR - ballR * 1.2f)).coerceAtMost(1f)
                    return Vec2((pull.x / dist * power).toDouble(), (pull.y / dist * power).toDouble())
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var shot = shotAt(down.position)
                    var step = shot?.let { (it.len() * 10).toInt() } ?: -1
                    currentOnAim(shot)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        shot = shotAt(change.position)
                        val newStep = shot?.let { (it.len() * 10).toInt() } ?: -1
                        if (newStep != step) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        step = newStep
                        currentOnAim(shot)
                    }
                    currentOnAim(null)
                    shot?.let(currentOnShoot)
                }
            }
    ) {
        val c = center
        val ballR = PAD_BALL_R.toPx()
        val maxR = size.width / 2f - 6.dp.toPx()
        val alpha = if (enabled) 1f else 0.35f
        // Power rings: inner = softest, outer = full power.
        for (i in 1..4) {
            val r = ballR * 1.2f + (maxR - ballR * 1.2f) * i / 4f
            drawCircle(powerColor(i / 4f).copy(alpha = 0.35f * alpha), r, c, style = Stroke(if (i == 4) 2.dp.toPx() else 1.dp.toPx()))
        }
        val shot = aim
        if (shot != null) {
            val power = shot.len().toFloat()
            val dir = Offset(shot.x.toFloat(), shot.y.toFloat()) / power
            val col = powerColor(power)
            val finger = c - dir * (ballR * 1.2f + (maxR - ballR * 1.2f) * (power - 0.04f) / 0.96f)
            // Rubber band to the finger, short arrow the way the ball will go.
            drawLine(Color.White.copy(alpha = 0.5f), c, finger, 2.dp.toPx(), StrokeCap.Round)
            drawCircle(col, 6.dp.toPx(), finger)
            val tip = c + dir * (ballR + 10.dp.toPx() + 22.dp.toPx() * power)
            val head = 7.dp.toPx() + 5.dp.toPx() * power
            val side = Offset(-dir.y, dir.x)
            drawLine(col, c + dir * ballR, tip - dir * head, (3 + 2 * power).dp.toPx(), StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(tip.x, tip.y)
                (tip - dir * head + side * head * 0.7f).let { lineTo(it.x, it.y) }
                (tip - dir * head - side * head * 0.7f).let { lineTo(it.x, it.y) }
                close()
            }, col)
        }
        drawCircle(Color(0x55000000), ballR, c + Offset(2.dp.toPx(), 3.dp.toPx()))
        drawCircle(ballColor.copy(alpha = alpha), ballR, c)
        drawCircle(Color.White.copy(alpha = alpha), ballR, c, style = Stroke(2.dp.toPx()))
    }
}
