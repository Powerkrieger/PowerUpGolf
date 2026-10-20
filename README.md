# PowerUp Golf

Party mini golf for 1–6 phones on the same Wi-Fi (or one phone's hotspot). No Google services.
Play alone against bots, or fill up a multiplayer game with them.

## Rules
- Everyone plays the same hole at once, taking turns one stroke at a time. Balls stay on the course and collide.
- Everyone tees off from the same spot, one after another: a ball still lying on the tee is nudged one ball
  length aside. While someone aims, everyone sees their arrow.
- Max 5 strokes per hole. The best score on the previous hole goes first.
- Hole in one 500, then 400/300/200/100; not holed 0.
- Bonus: bump an opponent +25, knock one into water +50, targeted power-up +30, your tornado/mine catching someone +40.
- Roll over 🎁 boxes for power-ups (2 slots); a taken box is back as soon as that turn ends. Fire them any time balls are on the course, even on someone
  else's turn or while balls roll. Tornado, Portal, Enlarge, Force Up,
  Ice Patch, Mine, Boulder, Earthquake, Swap, Magnet, Countdown (whoever is aiming has 5 s left or loses the turn).
- The cup sits in a funnel: slow balls nearby roll in.
- Shoot with the pad at the bottom: pull away from the ball like a slingshot; distance from the ball = power.
- The host can add 🤖 bots in the lobby: Easy, Medium or Hard. Harder bots search more shots, aim more precisely
  and use their power-ups more often and more cleverly.
- Course features: walls, bumpers, sand, water, ice, slopes, windmills, portal pairs, sliding jigsaw blades, boost pads.
  Windmills and blades never stop, not even between shots, and sweep resting balls along.

## Networking
Connections come from [PowerLink](https://github.com/Powerkrieger/PowerLink), a git submodule in `powerlink/`
shared with the other games: Wi-Fi/hotspot, Bluetooth and Wi-Fi Direct, no Google services.
Clone with `git clone --recursive`, or run `git submodule update --init` after cloning.
Fix connection problems in PowerLink, then `git submodule update --remote powerlink` here.

The host phone runs the authoritative `Match` and physics. Messages are JSON lines; the host streams the
full `MatchState` (~30 Hz during a hole, since the obstacles keep moving) and phones interpolate.

## Code
- `game/` – pure Kotlin, no Android: `Levels` (add holes here), `Physics`, `Match` (rules), `PowerUp`,
  `Bot` (simulates candidate shots) and `BotBrain` (what a computer player does, per `BotLevel`).
- `net/` – `GameHost`, `GameClient` (on top of PowerLink `Link`s), the wire protocol.
- `ui/` – Compose screens and the `CourseRenderer` canvas.

The host drives bots: `GameHost` asks `BotBrain` for a move off the game loop and sends it in as an `Intent`,
just like a player's phone would.

Tests (`./gradlew :app:testDebugUnitTest`) include a brute-force bot that checks every hole can be finished
within 5 strokes, so run them after adding levels. `LevelPreview` renders every hole to
`app/build/level-previews/` for a quick look at a layout without a phone.
