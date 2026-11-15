# Changelog

What changed in each version of the Ideomancer's Machine Mod, newest first. Downloads are on the
[Releases page](https://github.com/Socratique/Ideomancers-Machine-Mod/releases) and on CurseForge.
Details of every feature are in the [README](README.md).

## Unreleased

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
  Nether the highest block is the roof, so arrivals land on the roof.
- A powered empty Fluid Pipe took the water out of a waterlogged block by deleting the whole block (a
  slab, a frame, a chest). It now only takes real water and lava source blocks.
- A Fluid Pipe could place water in the Nether. Its water now dries up there with a hiss and smoke, as
  from a bucket.
- Walking on a Framed Redstone Ore turned it into plain redstone ore, losing the frame. It now stays
  framed (and doesn't light up when stepped on).
- A framed block or Hollowstone with a malformed inner block id (only possible through commands)
  crashed every client that drew it. It now counts as empty.

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
