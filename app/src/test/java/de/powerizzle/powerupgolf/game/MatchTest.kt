package de.powerizzle.powerupgolf.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {
    private var now = 1_000L

    private fun newMatch(vararg names: String): Match {
        val m = Match("Host", seed = 7)
        m.join("Host", 0)
        names.forEach { m.join(it, 0) }
        m.handle(Intent.Start(HOST_ID), now)
        return m
    }

    private fun Match.state() = snapshot(now)

    /** Plays the current player's turn with the bot's best shot. */
    private fun Match.botTurn() {
        val s = state()
        val id = s.currentId!!
        val b = s.player(id)!!.ball!!
        val r = Bot(s.level).best(Vec2(b.x, b.y), s.simTime)
        handle(Intent.Shoot(id, r.shot.x, r.shot.y), now)
        settleNow(now)
    }

    @Test
    fun fullGameFlow() {
        val m = newMatch("A", "B")
        assertEquals(Phase.AIMING, m.state().phase)
        assertEquals(listOf(0, 1, 2), m.state().order)
        repeat(Levels.all.size) { hole ->
            var guard = 0
            while (m.state().phase != Phase.HOLE_DONE) {
                m.botTurn()
                assertTrue("hole $hole never ends", ++guard < 40)
            }
            val s = m.state()
            for (p in s.players) {
                assertTrue(p.done)
                assertEquals(hole + 1, p.holeScores.size)
                assertTrue(p.holeScores.last() >= holePoints(p.strokes, p.holed))
            }
            val expectedOrder = s.players.sortedByDescending { it.holeScores.last() }.map { it.id }
            now += HOLE_DONE_SECONDS * 1000L + 10
            m.tick(now)
            if (hole < Levels.all.size - 1) {
                assertEquals(hole + 1, m.state().levelIndex)
                assertEquals("best last score plays first", expectedOrder, m.state().order)
            }
        }
        assertEquals(Phase.GAME_OVER, m.state().phase)
        m.state().players.forEach { assertEquals(it.holeScores.sum(), it.total) }
    }

    @Test
    fun turnsRotateAndTimeoutCostsAStroke() {
        val m = newMatch("A")
        assertEquals(0, m.state().currentId)
        m.handle(Intent.Shoot(1, 0.0, -0.3), now) // not B's turn: ignored
        assertEquals(0, m.state().player(1)!!.strokes)
        m.handle(Intent.Shoot(0, 0.0, -0.2), now)
        m.settleNow(now)
        assertEquals(1, m.state().currentId)
        m.tick(now)
        now += TURN_SECONDS * 1000L + 10
        m.tick(now)
        assertEquals(1, m.state().player(1)!!.strokes)
        assertEquals(0, m.state().currentId)
        assertEquals(2, m.state().round)
    }

    @Test
    fun fiveStrokesMax() {
        val m = newMatch()
        repeat(MAX_STROKES) {
            m.handle(Intent.Shoot(0, 1.0, 0.0), now)
            m.settleNow(now)
        }
        assertEquals(Phase.HOLE_DONE, m.state().phase)
        assertEquals(0, m.state().player(0)!!.holeScores.last())
    }

    @Test
    fun ballsTeeOffOneAfterAnother() {
        val m = newMatch("B")
        val tee = Levels.all[0].tee
        assertEquals(tee, m.ballOf(0))
        assertNull("B's ball waits for B's turn", m.ballOf(1))
        m.handle(Intent.Shoot(0, 0.0, 0.0), now) // host doesn't move at all
        m.settleNow(now)
        assertEquals(tee, m.ballOf(1))
        assertEquals("host's ball was nudged one ball length aside", 2 * BALL_R + 0.2, m.ballOf(0)!!.dist(tee), 1e-6)
    }

    @Test
    fun hittingAnOpponentScores() {
        val m = newMatch("B")
        // Host putts a little forward and gets nudged on along the line when B tees; B shoots straight into it.
        m.handle(Intent.Shoot(0, 0.0, -0.05), now)
        m.settleNow(now)
        m.handle(Intent.Shoot(1, 0.0, -0.5), now)
        m.settleNow(now)
        assertEquals(Points.HIT, m.state().player(1)!!.bonus)
    }

    @Test
    fun theCourseKeepsMovingBetweenShots() {
        val m = newMatch()
        m.tick(now)
        val t0 = m.state().simTime
        now += 50
        m.tick(now)
        assertEquals(Phase.AIMING, m.state().phase)
        assertTrue("time runs while aiming", m.state().simTime > t0 + 0.04)
    }

    @Test
    fun othersSeeTheAim() {
        val m = newMatch("B")
        m.handle(Intent.Aim(1, 0.3, 0.0), now)
        assertNull("not B's turn", m.state().aim)
        m.handle(Intent.Aim(0, 0.3, -0.4), now)
        assertEquals(AimState(0.3, -0.4), m.state().aim)
        m.handle(Intent.Shoot(0, 0.3, -0.4), now)
        assertNull(m.state().aim)
    }

    @Test
    fun enlargedBallCannotDrop() {
        val m = newMatch("B")
        m.handle(Intent.Shoot(0, 0.0, 0.0), now) // so B's ball is on the tee
        m.settleNow(now)
        m.giveForTest(0, PowerUp.ENLARGE)
        m.handle(Intent.UsePower(0, 0, 1, null, null), now)
        m.settleNow(now)
        assertEquals(Points.SABOTAGE, m.state().player(0)!!.bonus)
        assertEquals(BALL_R * 2.1, m.state().player(1)!!.ball!!.r, 1e-9)
        assertTrue(m.state().player(0)!!.inventory.isEmpty())
        // Still B's turn after the power-up, and another one would be fine too.
        assertEquals(1, m.state().currentId)
        m.giveForTest(0, PowerUp.FORCE_UP)
        m.handle(Intent.UsePower(0, 0, 1, null, null), now)
        assertTrue(m.state().player(1)!!.forceUp)
    }

    @Test
    fun powerUpsWorkOnSomeoneElsesTurn() {
        val m = newMatch("B")
        assertEquals(0, m.state().currentId)
        m.giveForTest(1, PowerUp.FORCE_UP)
        m.handle(Intent.UsePower(1, 0, 0, null, null), now)
        assertTrue("B strikes during the host's turn", m.state().player(0)!!.forceUp)
        assertEquals(Points.SABOTAGE, m.state().player(1)!!.bonus)
    }

    @Test
    fun powerUpsWorkWhileBallsRoll() {
        val m = newMatch("B")
        m.handle(Intent.Shoot(0, 0.0, -0.6), now)
        assertEquals(Phase.ROLLING, m.state().phase)
        m.giveForTest(1, PowerUp.QUAKE)
        m.handle(Intent.UsePower(1, 0, null, null, null), now)
        assertTrue(m.state().player(1)!!.inventory.isEmpty())
        assertEquals("the roll in progress just continues", Phase.ROLLING, m.state().phase)
        m.settleNow(now)
        assertEquals("the shot still ends the host's turn", 1, m.state().currentId)
    }

    @Test
    fun clockKeepsRunningThroughAPowerRoll() {
        val m = newMatch("B")
        m.tick(now)
        now += 30_000
        m.giveForTest(1, PowerUp.QUAKE)
        m.handle(Intent.UsePower(1, 0, null, null, null), now)
        assertEquals(Phase.ROLLING, m.state().phase)
        now += 4_000
        m.settleNow(now)
        assertEquals(Phase.AIMING, m.state().phase)
        assertEquals("back to the host, not to B", 0, m.state().currentId)
        assertTrue(m.state().secondsLeft <= 6)
    }

    @Test
    fun countdownRushesTheAimer() {
        val m = newMatch("B")
        m.tick(now)
        m.giveForTest(1, PowerUp.COUNTDOWN)
        m.giveForTest(0, PowerUp.COUNTDOWN)
        m.handle(Intent.UsePower(0, 0, null, null, null), now)
        assertEquals("not on your own turn", 1, m.state().player(0)!!.inventory.size)
        m.handle(Intent.UsePower(1, 0, null, null, null), now)
        assertEquals(COUNTDOWN_SECONDS, m.state().secondsLeft)
        assertEquals(Points.SABOTAGE, m.state().player(1)!!.bonus)
        now += COUNTDOWN_SECONDS * 1000L + 10
        m.tick(now)
        assertEquals("host lost the turn", 1, m.state().currentId)
        assertEquals(1, m.state().player(0)!!.strokes)
    }

    @Test
    fun noPowerUpsBetweenHoles() {
        val m = newMatch()
        repeat(MAX_STROKES) {
            m.handle(Intent.Shoot(0, 1.0, 0.0), now)
            m.settleNow(now)
        }
        assertEquals(Phase.HOLE_DONE, m.state().phase)
        m.giveForTest(0, PowerUp.MAGNET)
        m.handle(Intent.UsePower(0, 0, null, null, null), now)
        assertEquals(1, m.state().player(0)!!.inventory.size)
    }

    @Test
    fun portalMovesTheVictim() {
        val m = newMatch("B")
        m.handle(Intent.Shoot(0, 0.0, 0.0), now)
        m.settleNow(now)
        val before = m.ballOf(1)
        m.giveForTest(0, PowerUp.PORTAL)
        m.handle(Intent.UsePower(0, 0, 1, null, null), now)
        val after = m.ballOf(1)!!
        assertNotEquals(before, after)
        assertTrue(after.dist(Levels.all[0].cup) > 30)
    }

    @Test
    fun mineBlastsOpponentsForPoints() {
        val m = newMatch("B")
        m.giveForTest(0, PowerUp.MINE)
        val b = Levels.all[0].tee
        // Mine right in front of the tee; host stays put (and is nudged aside), then B shoots over it.
        m.handle(Intent.UsePower(0, 0, null, b.x, b.y - 6), now)
        assertEquals(1, m.state().hazards.size)
        m.handle(Intent.Shoot(0, 0.0, 0.0), now)
        m.settleNow(now)
        m.handle(Intent.Shoot(1, 0.0, -0.4), now)
        m.settleNow(now)
        assertTrue(m.state().hazards.isEmpty())
        assertEquals(Points.HAZARD_VICTIM, m.state().player(0)!!.bonus)
    }

    @Test
    fun cantJoinRunningGame() {
        val m = newMatch()
        assertNull(m.join("late", 0))
        assertFalse(m.state().players.any { it.name == "late" })
    }
}
