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
    fun hittingAnOpponentScores() {
        val m = newMatch("B")
        // Host stands left of B on the tee; shoot straight right into B.
        m.handle(Intent.Shoot(0, 0.5, 0.0), now)
        m.settleNow(now)
        assertEquals(Points.HIT, m.state().player(0)!!.bonus)
    }

    @Test
    fun enlargedBallCannotDrop() {
        val m = newMatch("B")
        m.giveForTest(0, PowerUp.ENLARGE)
        m.handle(Intent.UsePower(0, 0, 1, null, null), now)
        m.settleNow(now)
        assertEquals(Points.SABOTAGE, m.state().player(0)!!.bonus)
        assertEquals(BALL_R * 2.1, m.state().player(1)!!.ball!!.r, 1e-9)
        assertTrue(m.state().player(0)!!.inventory.isEmpty())
        // Still host's turn after using the power-up; a second one is not allowed.
        assertEquals(0, m.state().currentId)
        assertTrue(m.state().usedPowerThisTurn)
    }

    @Test
    fun portalMovesTheVictim() {
        val m = newMatch("B")
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
        val b = m.ballOf(1)!!
        // Mine right in front of B; host passes, then B shoots over it.
        m.handle(Intent.UsePower(0, 0, null, b.x, b.y - 6), now)
        assertEquals(1, m.state().hazards.size)
        m.handle(Intent.Shoot(0, 0.0, -0.05), now)
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
