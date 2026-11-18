# Changelog

What changed in each version of the Ideomancer's Machine Mod, newest first. Downloads are on the
[Releases page](https://github.com/Socratique/Ideomancers-Machine-Mod/releases) and on CurseForge.
Details of every feature are in the [README](README.md).

## Unreleased

### Added

- The mod's logo in the in-game mod list.

### Changed

- The Machine Track carries a pulse instead of holding a signal (the Ideomancer's original idea for it). A
  redstone signal turning on next to a Track sends a pulse along the line, one Track a tick, and every
  Trolley it passes moves one block, away from where the pulse came from. A line can be any length (the old
  signal faded by 1 per Track, so a line stopped after 15); the pulse passes each Track once, so loops and
  branches that meet again don't trap it; a signal held on gives one pulse. The grey indicator now shows the
  pulse running down the line.

### Fixed

- A Toggle, Timer, Repeater or Pulser ignored a pulse shorter than its 2-tick reaction time: the signal had
  to still be on when it acted. A Transmitter receiver's pulses, for one, never flipped a Toggle. Every
  rising edge now counts: a Toggle flips, a Timer starts, a Pulser fires, and a Repeater passes the pulse on
  for its delay, as a vanilla repeater does.
- A machine block set down by a piston while on seemed, for a moment, to give out a signal on every side,
  its input side included, before the rest of its state came back. A ring of Pulsers moved mid-loop could
  fire backwards. It now stays silent for that moment.
- An Engine moved by a piston restarted its count each time it landed (for a moment it saw no signal while
  the rest of the group was still landing), so on a machine moved every few ticks it never fired. It now
  pauses while moving and carries on counting after.
- A Toggle, Timer or Repeater that had a pulse waiting when a piston moved it now acts on it once it lands
  (a pulse arriving the tick before a move was lost, so a moving circuit could miss a flip).
- Lighting a portal with an ender pearl could teleport you a few blocks away. When the new portal linked at
  once, the pearl (used up, as it should be) was still inside the opening, and the portal sent a fresh copy of
  it through to the partner; that copy landed there and teleported you to it, like any pearl. A used-up pearl
  now stays used up.

## 0.1.1 (2026-11-17) — Alpha

Fixes from a code audit before the first CurseForge release: every bug below was reproduced in game first,
and every fix checked in game and by an automated test. Same requirements as 0.1.0 (Minecraft 1.20.1,
Forge 47 or later, client and server). A 0.1.0 game and a 0.1.1 server (or the other way round) refuse
to connect: the network version changed with the config sync below.

### Fixed

- A machine block caught mid-move when the world was saved and closed (or its chunk unloaded) landed
  empty: Machine Storage contents, Engine fuel, the slot item and settings were lost. The moving block
  now carries them in the save.
- A machine or framed block pushed up out of the top of the world, or down out of the bottom, was
  deleted with its contents. It now refuses to move there, as any vanilla block does.
- A mistake in `config/machinemod/machinemod.json` (a missing comma, say) crashed the game or server at the
  next piston move. The mistake is now logged and the settings that could be read are kept; a value of
  the wrong type falls back to its default. `/machinemod reload` says when the file had a problem.
- An unlinked portal threw things into the ground near the bottom of the world (the far spot's height
  was read before its chunk was loaded). They now land on the surface, inside the world border. In the
  End, a spot over the void is skipped for another; if every try is void, nothing is thrown. In the
  Nether, where the highest block is the bedrock roof, arrivals land at a random place to stand under
  the roof (a solid floor with room above it), and on the roof only if no such place is found.
- Portals did not move players in survival or adventure: walking in, a player stayed where they were
  (an unlinked portal still took its 5 hearts, again every few seconds while they stood in it), or
  walked out of the back of a linked one. The teleport ran in the middle of the server handling the
  player's movement, which put them back. Players now go through just after it, as with vanilla
  portals. Creative players, mobs and items were not affected.
- A powered empty Fluid Pipe took the water out of a waterlogged block by deleting the whole block (a
  slab, a frame, a chest). It now only takes real water and lava source blocks.
- A Fluid Pipe could place water in the Nether. Its water now dries up there with a hiss and smoke, as
  from a bucket.
- Walking on a Framed Redstone Ore turned it into plain redstone ore, losing the frame. It now stays
  framed (and doesn't light up when stepped on).
- A framed block or Hollowstone with a malformed inner block id (only possible through commands)
  crashed every client that drew it. It now counts as empty.
- A filled Hollowstone mined with a pickaxe dropped no shards (the block inside was put back before
  the drops). It now drops its shards and leaves the block inside standing. Broken with Silk Touch on a
  tool that can't harvest it (a shovel), it no longer destroys the block inside.
- Taking the Oddstone out of a powered source Transmitter through its GUI (or with shift-click) left
  its channel's receivers switched on. They now switch off.
- `timer.default_index` in the config had no effect: new Timers always started at 20 ticks. New
  Timers now start at the configured default; Timers already placed keep their setting.
- A portal with copper corners closed itself when a corner oxidised. Copper now counts as copper at
  any stage of oxidation, waxed or not, both for lighting and for the address.
- Breaking Oddstone that was only placed against a portal's face (not part of its frame) closed the
  portal. Only frame blocks close it now.
- Shift-clicking a stack into a Machine Base put one item in its slot, and then, since the Base had
  just become a Pipe or Storage, poured the rest of the stack into it. It now stops after the one.
- A sticky piston's pull stored machine data for linked Oddstone left behind over the push limit,
  under a position nothing was moving into; a later block arriving there could pick it up.
- Two pistons starting in the same moment could make the first one's framed blocks render hollow while
  moving.
- Two machine blocks changing in the same tick could leave a frame touching only the second one in
  the wrong state.
- A Machine Piston's head could overwrite a block another piston was sliding into its spot (the block
  was lost).
- Machine settings (Repeater delay, Transformer level, Timer and Engine intervals) could be changed by a
  player who didn't have the machine's menu open, or by a spectator. Only the open menu changes them now.
- Leaving a single-player world kept part of it in memory until the game closed.
- Every sticky-piston pull (with or without this mod's blocks) gave the blocks beside the piston head
  an extra update that vanilla doesn't, which observers and update-detector builds could see. Pulls now
  behave exactly as vanilla.
- The Machine Sticky Piston could pull Oddstone directly, which a vanilla sticky piston can't. It no
  longer does (Oddstone dragged along by a pulled group still moves).

### Added

- On a server, each player who joins now uses the server's settings from `machinemod.json` while connected
  (their own file is left alone and comes back when they leave), and `/machinemod reload` sends new
  settings to everyone. Before, a player whose file differed saw linked Oddstone pop instead of slide
  and could set the wrong Timer or Engine time. Players need this version to join a server running it.
  From Ideomod.

### Faster (nothing plays differently)

- Machine Transmitters find each other from the list of machines each chunk already keeps, instead of
  checking every block in range (1,330 blocks per update at the default range, 15,624 at range 12).
- Each piston move works out which blocks move once, using vanilla's own answer, instead of two or
  three times.
- Item and Fluid Pipes only check for redstone when they have something to do; a burning Engine no
  longer alerts nearby comparators every tick (only when its fuel or look changes).

### Changed

- Hoppers no longer take the item out of a machine (a hopper under a Machine Drill used to take its
  pickaxe, turning it back into a Machine Base). They can still put the item into an empty Machine
  Base, and still take from a Machine Storage's or Pipe's own slots.
- Fortune on Hollowstone gives at most 9 shards, the number that crafts one Hollowstone (it averaged
  12 with Fortune III, up to 28).
- The in-game mod list links to the CurseForge page and to GitHub Issues for bug reports.

## 0.1.0 (2026-11-15) — Alpha

The first release. A work in progress: things will change, and some parts are rough
(see [Known rough edges](README.md#known-rough-edges)).

Needs Minecraft 1.20.1 and Forge 47 or later, on both the client and the server.

- **Oddstone** (with Cobbled Oddstone and Oddstone Bricks, and their slabs, stairs and walls): when anything moves
  an Oddstone block, every Oddstone block of the same type in a straight line from its faces, up to 12 blocks away,
  moves with it. Land mobs won't walk over it, it doesn't conduct redstone, and it grabs slime and honey but they
  can't grab it.
- **Odd Slime:** a slime block that is only sticky in the direction it faces.
- **Oddstone Repulser and Attractor:** push and pull Oddstone from up to 12 blocks away.
- **Oddstone Portals:** a ring of Cobbled Oddstone with a mineral block on each corner. The corners are the address:
  two portals with the same corners link. Players, mobs, items, projectiles and empty minecarts and boats go through.
- **Hollowstone:** forms where a lava source touches Oddstone, and makes the Machine Base.
- **Machine blocks:** a Machine Base becomes one of 20 machines depending on the item in its slot, and pistons move
  them with their contents and settings, so flying machines carry their own circuits:
  - Redstone: Conductor, Repeater, Toggle, Transformer, Timer, Pulser, Engine, Transmitter
  - Movement: Machine Piston, Machine Sticky Piston, Brace, Track, Trolley, Slime Grabber, Honey Grabber
  - Mining and storage: Machine Drill, Machine Storage
  - Steering: **Machine Seat** (any boat): sit in it, and your move keys send a redstone pulse out of the side
    they point to ([video](https://www.youtube.com/watch?v=yWvFrCX8fyo))
  - Transport: Item Pipe, Fluid Pipe
- **Iron and Copper Frames:** see-through blocks that stick to each other and to machine blocks only while a
  machine block activates them ([video](https://www.youtube.com/watch?v=DoqsGxybqKY)). **Framed Blocks** keep the
  block inside them working: framed slime stays sticky, framed obsidian anchors a contraption.
- Works alongside Ideomod: if both are installed this mod steps aside, and worlds move between them.
