package de.powerizzle.powerupgolf.game

/**
 * The course. Coordinates are in course units: x 0..100, y 0..160, y pointing down (tee at the bottom).
 * Add new holes to [all]; everything else (scoring, hole count, UI) picks them up automatically.
 */
object Levels {

    private val warmUp = Level(
        name = "Warm-Up Green",
        description = "A straight shot. Mind the bumpers.",
        outline = listOf(v(20, 10), v(80, 10), v(90, 20), v(90, 140), v(80, 150), v(20, 150), v(10, 140), v(10, 20)),
        tee = v(50, 136),
        cup = v(50, 32),
        bumpers = listOf(Bumper(v(32, 80), 4.0), Bumper(v(68, 80), 4.0), Bumper(v(50, 62), 3.0)),
        boxes = listOf(v(24, 105), v(76, 105), v(50, 95)),
    )

    private val dogleg = Level(
        name = "Dogleg",
        description = "Bank it off the angled corner wall for a hole in one.",
        outline = listOf(v(10, 150), v(48, 150), v(48, 54), v(90, 54), v(90, 10), v(24, 10), v(10, 24)),
        tee = v(29, 137),
        cup = v(76, 30),
        bumpers = listOf(Bumper(v(62, 22), 3.0)),
        zones = listOf(Zone(Terrain.SAND, rect(56, 38, 68, 52))),
        boxes = listOf(v(29, 100), v(29, 45), v(80, 46)),
    )

    private val pond = Level(
        name = "Pond Island",
        description = "Go around the pond, left or right. Or don't.",
        outline = listOf(v(18, 10), v(82, 10), v(92, 20), v(92, 140), v(82, 150), v(18, 150), v(8, 140), v(8, 20)),
        tee = v(50, 137),
        cup = v(50, 30),
        bumpers = listOf(Bumper(v(40, 44), 3.0), Bumper(v(60, 44), 3.0)),
        zones = listOf(
            Zone(Terrain.WATER, listOf(v(36, 60), v(64, 60), v(74, 70), v(74, 92), v(64, 102), v(36, 102), v(26, 92), v(26, 70))),
            Zone(Terrain.SAND, rect(10, 36, 24, 52)),
            Zone(Terrain.SAND, rect(76, 36, 90, 52)),
        ),
        boxes = listOf(v(16, 81), v(84, 81), v(50, 116)),
    )

    private val switchback = Level(
        name = "Switchback",
        description = "Right, left, up. The middle lane slopes, the top lane is frozen.",
        outline = listOf(v(10, 10), v(90, 10), v(90, 150), v(10, 150)),
        tee = v(36, 136),
        cup = v(72, 30),
        walls = listOf(Wall(v(10, 108), v(64, 108)), Wall(v(36, 62), v(90, 62))),
        zones = listOf(
            Zone(Terrain.ICE, rect(10, 10, 46, 60)),
            Zone(Terrain.SAND, rect(60, 42, 86, 50)),
        ),
        slopes = listOf(Slope(rect(10, 63, 90, 107), v(-18, 0))),
        bumpers = listOf(Bumper(v(50, 85), 3.5), Bumper(v(80, 25), 2.5)),
        boxes = listOf(v(80, 128), v(68, 90), v(20, 40)),
    )

    private val gauntlet = Level(
        name = "Windmill Gauntlet",
        description = "Through the windmill, past the water, into the fortress.",
        outline = listOf(v(18, 10), v(82, 10), v(90, 18), v(90, 142), v(82, 150), v(18, 150), v(10, 142), v(10, 18)),
        tee = v(50, 138),
        cup = v(50, 23),
        walls = listOf(
            Wall(v(40, 15), v(40, 32)), Wall(v(60, 15), v(60, 32)), Wall(v(40, 15), v(60, 15)),
            Wall(v(40, 32), v(45, 32)), Wall(v(55, 32), v(60, 32)),
        ),
        bumpers = listOf(Bumper(v(37, 42), 2.5), Bumper(v(63, 42), 2.5)),
        zones = listOf(
            Zone(Terrain.WATER, rect(10, 48, 31, 118)),
            Zone(Terrain.WATER, rect(69, 48, 90, 118)),
        ),
        slopes = listOf(
            Slope(rect(31, 52, 50, 114), v(-14, 0)),
            Slope(rect(50, 52, 69, 114), v(14, 0)),
        ),
        windmills = listOf(Windmill(v(50, 83), length = 14.0, arms = 3, speed = 1.4)),
        boxes = listOf(v(22, 32), v(78, 32), v(50, 125)),
        funnel = 8.0,
    )

    val all: List<Level> = listOf(warmUp, dogleg, pond, switchback, gauntlet)
}
