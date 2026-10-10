# PowerUp Golf

Party mini golf for 1–6 phones on the same Wi-Fi (or one phone's hotspot). No Google services.

## Rules
- Everyone plays the same hole at once, taking turns one stroke at a time. Balls stay on the course and collide.
- Max 5 strokes per hole. The best score on the previous hole goes first.
- Hole in one 500, then 400/300/200/100; not holed 0.
- Bonus: bump an opponent +25, knock one into water +50, targeted power-up +30, your tornado/mine catching someone +40.
- Roll over 🎁 boxes for power-ups (2 slots, one use per turn, before shooting): Tornado, Portal, Enlarge, Force Up,
  Ice Patch, Mine, Boulder, Earthquake, Swap, Magnet.
- The cup sits in a funnel: slow balls nearby roll in.

## Networking
The host phone runs the authoritative `Match` and physics. Joiners find it via UDP broadcast (port 47411)
or by typing the IP shown in the lobby, then connect over TCP (port 47410). Messages are JSON lines;
the host streams the full `MatchState` (~30 Hz while balls roll) and phones interpolate.

## Code
- `game/` – pure Kotlin, no Android: `Levels` (add holes here), `Physics`, `Match` (rules), `PowerUp`.
- `net/` – `GameHost`, `GameClient`, `Discovery`.
- `ui/` – Compose screens and the `CourseRenderer` canvas.

Tests (`./gradlew :app:testDebugUnitTest`) include a brute-force bot that checks every hole can be finished
within 5 strokes, so run them after adding levels.
