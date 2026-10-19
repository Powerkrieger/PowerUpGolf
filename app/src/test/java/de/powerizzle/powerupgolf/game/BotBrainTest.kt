package de.powerizzle.powerupgolf.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BotBrainTest {
    private var now = 1_000L

    @Test
    fun botsJoinAndLeaveInTheLobbyOnly() {
        val m = Match("Host", seed = 3)
        m.join("Host", 0)
        val easy = m.addBot(BotLevel.EASY)!!
        val hard = m.addBot(BotLevel.HARD)!!
        val s = m.snapshot(now)
        assertEquals(BotLevel.EASY, s.player(easy)!!.bot)
        assertEquals("🤖 Chip", s.player(easy)!!.name)
        assertNull(s.player(HOST_ID)!!.bot)
        m.handle(Intent.RemoveBot(1, hard), now) // only the host may
        assertEquals(3, m.snapshot(now).players.size)
        m.handle(Intent.RemoveBot(HOST_ID, hard), now)
        assertEquals(2, m.snapshot(now).players.size)
        m.handle(Intent.Start(HOST_ID), now)
        assertNull(m.addBot(BotLevel.MEDIUM))
    }

    @Test
    fun botsAmbushWhoeverIsAiming() {
        val m = Match("Host", seed = 5)
        m.join("Host", 0)
        val bot = m.addBot(BotLevel.EASY)!!
        m.handle(Intent.Start(HOST_ID), now)
        m.giveForTest(bot, PowerUp.FORCE_UP)
        // Easy bots only sometimes strike; over many tries one must come.
        val brain = BotBrain(Random(1))
        val hit = (1..100).firstNotNullOfOrNull { brain.ambush(m.snapshot(now), bot, BotLevel.EASY) }!!
        assertEquals(HOST_ID, hit.targetId)
        m.handle(hit, now)
        assertTrue(m.snapshot(now).player(HOST_ID)!!.forceUp)
    }

    /** Bots of every level play a whole game against each other; harder ones should score more. */
    @Test
    fun botsPlayAWholeGame() {
        val totals = BotLevel.entries.associateWith { 0 }.toMutableMap()
        val report = StringBuilder()
        var slowest = 0L
        repeat(2) { game ->
            val m = Match("Host", seed = 11L + game)
            m.join("Host", 0)
            val ids = BotLevel.entries.associateBy { m.addBot(it)!! }
            m.handle(Intent.Start(HOST_ID), now)
            val brain = BotBrain(Random(game))
            var guard = 0
            while (m.currentPhase != Phase.GAME_OVER) {
                val s = m.snapshot(now)
                when (s.phase) {
                    Phase.AIMING -> {
                        val id = s.currentId!!
                        for ((other, l) in ids) if (other != id) brain.ambush(s, other, l)?.let { m.handle(it, now) }
                        if (m.currentPhase == Phase.ROLLING) {
                            m.settleNow(now)
                            continue
                        }
                        val level = ids[id]
                        if (level == null) {
                            m.handle(Intent.Shoot(id, 0.0, 0.0), now) // the host just stays put
                        } else {
                            val t0 = System.currentTimeMillis()
                            val first = brain.decide(m.snapshot(now), id, level, mayUsePower = true)
                            m.handle(first, now)
                            if (first is Intent.UsePower) {
                                m.settleNow(now)
                                m.handle(brain.decide(m.snapshot(now), id, level, mayUsePower = false), now)
                            }
                            slowest = maxOf(slowest, System.currentTimeMillis() - t0)
                        }
                        m.settleNow(now)
                    }
                    Phase.HOLE_DONE -> {
                        now += HOLE_DONE_SECONDS * 1000L + 10
                        m.tick(now)
                    }
                    else -> error("unexpected ${s.phase}")
                }
                assertTrue("game never ends", ++guard < 2000)
            }
            val s = m.snapshot(now)
            for ((id, level) in ids) {
                val p = s.player(id)!!
                totals[level] = totals.getValue(level) + p.total
                report.appendLine("game $game ${level.label}: ${p.total} (${p.holeScores.joinToString()})")
            }
        }
        report.appendLine("slowest turn: $slowest ms")
        println(report)
        assertTrue(report.toString(), totals.getValue(BotLevel.HARD) > totals.getValue(BotLevel.EASY))
    }
}
