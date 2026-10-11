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

    private val portalHop = Level(
        name = "Portal Hop",
        description = "No way through the wall. Pick a portal: the safe one, or the one behind the saw.",
        outline = listOf(v(18, 10), v(82, 10), v(90, 18), v(90, 142), v(82, 150), v(18, 150), v(10, 142), v(10, 18)),
        tee = v(50, 138),
        cup = v(38, 28),
        walls = listOf(Wall(v(10, 100), v(90, 100))),
        portals = listOf(Portal(v(20, 112), v(22, 86)), Portal(v(80, 112), v(64, 42))),
        sliders = listOf(
            Slider(v(68, 112), v(68, 132), half = 6.0, period = 2.6),
            Slider(v(70, 64), v(84, 64), half = 6.0, period = 3.0),
        ),
        bumpers = listOf(Bumper(v(50, 116), 3.0), Bumper(v(38, 44), 2.5)),
        zones = listOf(
            Zone(Terrain.WATER, rect(10, 56, 64, 70)),
            Zone(Terrain.SAND, rect(70, 18, 88, 34)),
        ),
        boxes = listOf(v(30, 124), v(78, 84), v(20, 30)),
    )

    private val jigsawAlley = Level(
        name = "Jigsaw Alley",
        description = "Right, up, left, up, right. Time your run past the saws.",
        outline = rect(10, 10, 90, 150),
        tee = v(24, 127),
        teeSpread = v(0, 1),
        cup = v(78, 32),
        walls = listOf(Wall(v(10, 104), v(70, 104)), Wall(v(30, 58), v(90, 58))),
        sliders = listOf(
            Slider(v(46, 117), v(46, 137), half = 11.0, period = 2.8),
            Slider(v(62, 137), v(62, 117), half = 11.0, period = 2.8),
        ),
        windmills = listOf(Windmill(v(46, 81), length = 12.0, arms = 2, speed = 1.6)),
        boosters = listOf(Booster(v(80, 90), v(0, -1)), Booster(v(20, 66), v(0, -1))),
        zones = listOf(
            Zone(Terrain.WATER, rect(76, 136, 90, 150)),
            Zone(Terrain.ICE, rect(30, 14, 64, 54)),
        ),
        bumpers = listOf(Bumper(v(68, 22), 2.5), Bumper(v(68, 44), 2.5)),
        boxes = listOf(v(34, 112), v(80, 120), v(62, 94), v(20, 30)),
    )

    private val spiral = Level(
        name = "Spiral",
        description = "Wind your way into the middle. The orange portal skips a lap, if you get past the saw.",
        outline = rect(10, 10, 90, 150),
        tee = v(50, 142),
        cup = v(50, 60),
        walls = listOf(
            Wall(v(26, 44), v(26, 134)), Wall(v(26, 134), v(74, 134)), Wall(v(74, 134), v(74, 26)), Wall(v(74, 26), v(44, 26)),
            Wall(v(42, 42), v(58, 42)), Wall(v(42, 42), v(42, 118)), Wall(v(42, 118), v(58, 118)), Wall(v(58, 42), v(58, 100)),
        ),
        portals = listOf(Portal(v(83, 142), v(66, 56))),
        sliders = listOf(Slider(v(74, 138.5), v(74, 145.5), half = 4.0, period = 2.2)),
        slopes = listOf(Slope(rect(42, 68, 58, 118), v(0, -22))),
        boosters = listOf(Booster(v(18, 96), v(0, -1)), Booster(v(66, 84), v(0, 1))),
        zones = listOf(
            Zone(Terrain.WATER, rect(80, 60, 90, 100)),
            Zone(Terrain.SAND, rect(44, 28, 56, 40)),
        ),
        boxes = listOf(v(18, 120), v(34, 80), v(82, 30), v(50, 126)),
    )

    private val finale = Level(
        name = "Grand Finale",
        description = "Saw, bridge, windmill, fortress. Or take the portal and pray.",
        outline = listOf(v(18, 10), v(82, 10), v(90, 18), v(90, 142), v(82, 150), v(18, 150), v(10, 142), v(10, 18)),
        tee = v(50, 140),
        cup = v(50, 26),
        walls = listOf(
            Wall(v(36, 16), v(36, 40)), Wall(v(64, 16), v(64, 40)), Wall(v(36, 40), v(45, 40)), Wall(v(55, 40), v(64, 40)),
        ),
        portals = listOf(Portal(v(16, 124), v(80, 24))),
        sliders = listOf(
            Slider(v(40, 122), v(60, 122), half = 5.0, period = 2.4),
            Slider(v(42, 46), v(58, 46), half = 4.0, period = 2.0, phase = 1.0),
        ),
        boosters = listOf(Booster(v(50, 105), v(0, -1))),
        windmills = listOf(Windmill(v(50, 74), length = 13.0, arms = 4, speed = 1.2)),
        bumpers = listOf(Bumper(v(22, 74), 3.0), Bumper(v(78, 74), 3.0), Bumper(v(16, 132), 2.5)),
        zones = listOf(
            Zone(Terrain.WATER, rect(10, 96, 44, 114)),
            Zone(Terrain.WATER, rect(56, 96, 90, 114)),
            Zone(Terrain.SAND, rect(12, 50, 28, 62)),
            Zone(Terrain.ICE, rect(66, 12, 88, 46)),
        ),
        boxes = listOf(v(76, 128), v(24, 86), v(76, 56), v(24, 30)),
        funnel = 8.0,
    )

    val all: List<Level> = listOf(warmUp, dogleg, pond, switchback, gauntlet, portalHop, jigsawAlley, spiral, finale)
}
