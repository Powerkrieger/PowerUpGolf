package de.powerizzle.powerupgolf.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {

    @Test
    fun scoring() {
        assertEquals(listOf(500, 400, 300, 200, 100), (1..5).map { holePoints(it, true) })
        assertEquals(0, holePoints(5, false))
    }

    @Test
    fun layoutIsSane() {
        for (level in Levels.all) {
            val bot = Bot(level)
            assertTrue(level.name, level.inside(level.cup))
            for (i in 0 until MAX_PLAYERS) {
                val t = level.teeSpot(i, MAX_PLAYERS)
                assertTrue("${level.name} tee $i", level.inside(t) && level.terrainAt(t) == Terrain.GRASS)
                assertNotEquals("${level.name} tee $i can't reach the cup", Int.MAX_VALUE, bot.walkingDistance(t))
                assertTrue("${level.name} tee $i too close to a wall",
                    level.allWalls.all { closestOnSegment(t, it.a, it.b).dist(t) > BALL_R + WALL_HALF_WIDTH })
            }
            for (b in level.boxes) assertTrue("${level.name} box $b", level.inside(b) && bot.walkingDistance(b) != Int.MAX_VALUE)
        }
    }

    /** A greedy bot must be able to finish every hole within the stroke limit. Prints how many it needed. */
    @Test
    fun everyHoleIsPlayable() {
        val report = StringBuilder()
        for (level in Levels.all) {
            val bot = Bot(level)
            var pos = level.tee
            var strokes = 0
            var time = 0.0
            var holed = false
            while (strokes < MAX_STROKES && !holed) {
                val r = bot.best(pos, time)
                strokes++
                time += 3.0
                if (r.end == null) holed = true else pos = r.end
            }
            report.appendLine("${level.name}: ${if (holed) "holed in $strokes" else "NOT holed, ended ${bot.walkingDistance(pos)} away"}")
            assertTrue("${level.name} not playable:\n$report", holed)
        }
        println(report)
    }
}
