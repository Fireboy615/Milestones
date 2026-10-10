# Milestone configuration — first phase

The editable configuration is created when the modded Minecraft server starts:

- `config/milestone/tasks.json` — your custom tasks
- `config/milestone/cores.json` — settings for the six automatic Core tiers

Template copies are bundled at `src/main/resources/defaults/milestone/`.
**Edit the instance's config files, not the bundled copies.**
Restart the game/server after changing either JSON file. The loader validates the entries and logs rejected definitions.

## Custom tasks

Each object in `tasks.json` requires a unique lowercase `id`, a descriptive `clue`, and a `type`:
- `item`: requires `item` (e.g. `minecraft:iron_ingot`) and `count`
- `kill`: requires `entity` (e.g. `minecraft:zombie`); one personal kill
- `advancement`: requires `advancement` (e.g. `minecraft:story/mine_diamond`); progress after assignment

`category` and `difficulty` are freeform labels, e.g. `gathering` and `easy`.
IDs in `task_ids` of a Core take precedence over its category/difficulty filter. IDs can reference original hardcoded tasks or custom tasks.

Example: `{"id":"collect_iron","type":"item","item":"minecraft:iron_ingot","count":16,"category":"gathering","difficulty":"easy","clue":"Offer sixteen iron ingots."}`

## Core configuration

Six Core positions, `tier` 0 to 5. Existing world-generation reservations are persisted and are **not reset or moved** by config changes.
- `enabled`: exclude a tier from planning *new* world sites (does not remove existing sites).
- `min_y`, `max_y`: height boundaries for unplaced cores.
- `min_distance`, `max_distance`: distance from spawn in blocks; both 0 keeps the original automatic distance rings.
- `depth_fraction`: fraction from surface downward (0 near surface, 1 deep).
- `profile`: `generic` or `nether_gate` (retains Nether progression mechanics).
- `difficulty` and `categories`: select custom tasks unless `task_ids` is nonempty. Matching is an exact string comparison; use `"*"` as difficulty to accept all.
- `task_ids`: list of exact task IDs. Empty list uses the filter.
- `name`, `color`, `schematic`: metadata reserved for upcoming custom display, rendering, and .nbt structure verification; they **do not yet change the Core's actual look, schematic or verification**.

If no custom task matches, the code falls back to the preexisting built-in profile task pool. Task assignments are saved by ID, but changing or deleting an active task definition can affect that player's ability to complete it. Maintain IDs until active assignments finish.

## Next work

1. Real per-Core custom schematic loading from files and generalized structure verifier (currently fixed 5×5 Create chamber).
2. Actual per-Core colour rendering and saved colour/profile identifiers for worlds.
3. Per-Core level/difficulty progression and reward thresholds beyond the existing two profiles.
4. Admin configuration screen, task-category navigation, diagnostic reload command.
5. Saved assignment definition snapshots/compatibility migration for live servers, and dedicated-server in-game testing.

Build command: `.\gradlew.bat build --offline --console=plain`. Successful builds auto-install to the configured CurseForge instance.
