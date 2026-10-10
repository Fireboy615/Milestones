# Milestone server configuration â€” dynamic task system

## Where to edit

Your currently configured server instance:
`C:\Users\sethd\curseforge\minecraft\Instances\Internshit Server\config\milestone\`

- `tasks.json`: define individual objectives and their difficulty/category tags
- `cores.json`: configure the configurable automatic Core profiles (0â€“5)

Template copies live in `src/main/resources/defaults/milestone/`. Editing a template alone does not override an existing instance config.

Use the operator-only `/milestoneconfig reload` command to reload the JSONs, or use the **Config** button at the Mission Station (only visible to admins/operators). The GUI can edit one Core field at a time; server-side validation is enforced before saving. Save feedback appears in chat.
`/milestoneconfig list` and `/milestoneconfig show <tier>` preview settings. This does **not** regenerate, reset or move existing placed Cores.

## Tasks

Example custom quest:

```json
{
  "id": "hunt_zombies_20",
  "category": "combat",
  "difficulty": "medium",
  "type": "kill",
  "entity": "minecraft:zombie",
  "count": 20,
  "clue": "Personally defeat twenty zombies."
}
```

Possible `type`:
- `kill`: Minecraft/modded entity ID and kill `count` (1â€“1000). Progress tracked per assigned player and saved across logins.
- `item`: item ID and `count` (1â€“2304), offered in the Mission Station.
- `advancement`: advancement resource ID, earned after assignment.

Categories and difficulties are configurable labels (e.g. `gathering`, `combat`, `exploration`; `easy`, `medium`, `hard`, `extreme`). Assignment is individual; Core progression is shared by every player on the server, regardless of teams.

## Configuring a Core

Each object in `cores.json` has its own `tier` (0â€“5), `name`, `enabled`, and:

- `task_mode: "preset"`: choose custom tasks by `difficulty` and optional `categories`. Use `"difficulty":"*"` for any difficulty.
- `task_mode: "manual"`: use exactly the task IDs in `task_ids`. Include at least one valid custom or built-in task ID.
- `requires_completed_cores: [0, 2]`: require both tier 0 and tier 2 to be **COMPLETED** before this Core's tasks unlock. No dependency cycles permitted.
- `dimension: "minecraft:overworld"`: choose a valid loaded dimension; for example `minecraft:the_nether` (only new sites; some terrain can be unsuitable).
- `allowed_biomes: ["minecraft:plains"]`: limit generation to these biome IDs. Empty = any allowed terrain. `blocked_biomes`: veto certain biome IDs.
- `min_distance`, `max_distance`: distance from that dimension's shared-spawn coordinates in blocks; 0/0 retains tier-based default ranges.
- `min_y`, `max_y`, `depth_fraction`: vertical placement boundaries and target depth relative to the surface.
- `profile: "generic"` or `"nether_gate"`: legacy threshold/unlock type. The Nether profile controls server portal unlocks.
- `reward_xp`: experience **points** immediately awarded to the player when their assigned contribution completes.
- `reward_items`: item rewards for each player contributing successfully, e.g. `[{"item":"minecraft:diamond","count":2}]`.
- `reward_commands`: server commands executed once when the Core becomes COMPLETED, e.g. `["say Tier cleared!"]`. Treat this config as server-operator-level access.
- `color`, `schematic`: values are parsed/saved, but custom visual colour rendering and custom `.nbt` structure validation **are not connected yet**. The generated Create 5Ã—5 chamber is still used. Keep `"schematic":"generated"` until the verifier supports imported builds.

In the in-game config GUI, lists use comma-separated values; command lists use `;;` between commands; reward items use `minecraft:item_id*count` separated by commas.

## Compatibility and operational cautions

Changes to spawn location, dimension, world-generation filters or Core type affect **new/unplaced sites** only. Existing world reservations and completed Cores persist as saved data. Existing Core assignments remain attached to saved task IDs, even when the selected pool is edited, as long as the definitions still exist. Avoid deleting or renaming active task IDs.

Any admin can change progression rules, so the GUI requires permission level 2 on the server. Every client receives enough task definitions to display their own assignments; console reward commands are withheld from non-admin clients.

A successful Gradle build automatically installs `milestone-0.1.0.jar` to the configured CurseForge instance's mods folder. **The mod compiles, but these new features still require multiplayer gameplay testing, especially extra dimensions and the new Config GUI.**

Potential next phase: custom NBT schematic loading + generalized verifier, then custom Core tint rendering and configurable levels/thresholds.


## Mods-list Config button
From the pause menu use Mods -> Milestone -> Config. This opens the same editor; Close returns to Mods. Server edits require operator permissions and an active world connection. At the title screen, view-only settings are available.

## New Mods-list configuration pages

The Mods -> Milestone -> Config editor now has a landing page modeled on the hand-drawn layout:
- Enabled ON/OFF: pauses Milestone site generation, discovery, new task assignment, and contribution handling without deleting saves; it does not unload the installed mod
- Cores: vanilla-style scrolling row list, Add Core at top, Delete beside each Core (confirmation required). Select a Core to open its directly editable, scrollable settings rows. IDs are stable, not renumbered when deleting
- Tasks: browse existing tasks, create and delete tasks, edit type, target, count, category, difficulty and clue
- Shop: editable Resonant Exchange catalog, contribution credits, optional Core unlocks and rarities
- Save: validates and writes the full tasks and cores configuration on the active server if you have operator permissions, or in the local instance when opened outside a world
- Cancel: discards everything staged in the editor and returns to the Mods list

On each editor page, Apply field stores the current value in the draft. Moving between fields or returning to the menu also applies the current text automatically. No changes are persisted until Save. Existing worlds and their saved Core locations are preserved. The configuration is validated on the server; errors are displayed in the menu.

## Vanilla-style Core list and editor (October 2026)

The Mods -> Milestone -> Config screen uses vanilla-style gray widgets, not the earlier blue panel.
Cores now opens a scrolling Core list with **+ Add Core**, direct Core buttons and a confirmed **Delete** beside each row. Select a Core to edit all settings using labeled rows and scroll to access more options; there are no field or Core-switch arrows. Tasks has a similar list and form. Edits are staged until **Save**; **Cancel** leaves the configuration untouched.

Core IDs range from 0 to 31 (displayed to admins as Core 1?32). New IDs are allocated above the highest configured ID; deleting one does not renumber others. Existing worlds automatically reserve newly added profiles on subsequent world-generation checks, without relocating already placed Cores. The live server refuses to delete any profile whose Core has been placed or has saved milestone progress; instead toggle that Core's Spawn enabled setting to false. Deleting an unused, unplaced profile also clears its reservation. Profiles referenced as another Core's prerequisite cannot be deleted until the dependency is edited.

**Important:** A successful compile/build is not a substitute for in-game visual testing. Also, this version's Shop section is only a placeholder, custom Core colors and imported NBT schematic verification remain planned.

## Editor readability, scrollbars and task grouping (October 2026)

The Core and Task row lists, and the Core settings form, now use a standard draggable scrollbar on the right whenever there are more entries than fit on screen. Click its track, drag the thumb, or use the mouse wheel. The old bottom-left navigation arrows have been removed. Text is drawn after the widget/background rendering pass to avoid the blurred labels reported in-game.

**Task editor:** The Category field is a button, not a text box. Click it to cycle through known categories. Click **+ New category** at the top of Task editing to create a reusable named category; it remains available even when it has no tasks. Difficulty is also a cycle button and supports built-in as well as existing custom difficulty labels. **Tasks list** has independent difficulty and category filter buttons so you can browse all tasks by rarity or show only tasks belonging to a particular category. Task rows show their difficulty.

**Core editor:** The Task categories row opens a category selector showing each category, a count of tasks it contains, and an [x]/[ ] checkbox-style selection button. Multiple categories can be enabled for a Core's preset task pool. The selector includes **+ New category**.

All such additions remain part of the draft until Save. Cancel discards the draft. Existing task JSON files remain readable; the optional new tasks.json top-level "categories" array is created when registering a category not already defined by an existing task.

Custom Core colours, NBT structure validation and the Shop gameplay are still unimplemented; these UI changes do not activate those features.

## Named Create schematics (October 2026)

A new **Schematics** tab is available at Mods -> Milestone -> Config -> Schematics.
You can:
- Choose **+ Add Schematic** to create an entry, then edit its stable ID, display name, and \`.nbt\` filename in labeled rows.
- Use **Import from Create** on the title screen or in singleplayer to browse existing compressed \`.nbt\` files inside Create's local schematics directory. Choosing one copies it into Milestone's dedicated schematic folder, registers the name, and opens it for editing.
- Use **Open NBT folder** to open the local config directory where \`.nbt\` files should be placed manually.
- Delete a library entry after detaching it from any Core. Deleting the entry never deletes the underlying \`.nbt\` file, to protect your work.

The authoritative schematic folder is:
\`config/milestone/schematics/\`

In the **Cores** editor, click the **Schematic** row to cycle through **Generated 5x5 Chamber** and all your named entries. The menu shows display names; the saved config keeps stable IDs. Select the Core's schematic and press Save on the main menu.

An entry in \`cores.json\` is represented like this (illustrative):
\`\`\`json
"schematics": [
  {"id":"village_hall","name":"Village Hall","file":"village_hall.nbt"}
]
\`\`\`
A Core's \`schematic\` setting is then \`"village_hall"\`.

**For dedicated servers:** Put the \`.nbt\` in the SERVER's \`config/milestone/schematics\` folder, not merely in your local client folder. Import from Create intentionally only works with a local/integrated server; remote admins must copy the file to the server. Every Save validates that each listed NBT file exists and contains a usable vanilla/Create structure format with \`size\`, \`palette\`, and \`blocks\`.

Imported structures are limited to dimensions of 24x24x24 maximum, up to 4,096 non-air blocks and 256 KiB compressed file size. Entity and block-entity NBT is rejected for safety (remove chests/spawners/machines with inventory or other block entities before exporting). The Mission Station issues a Create schematic using the selected file; the verifier checks the placed block states within 24 blocks horizontally and 8 blocks vertically of the Station. **No rotation is supported yet**: place the structure in the same orientation as the file. Air blocks are ignored when verifying.

The original generated 5x5 chamber remains the default for existing Cores. Already verified structures stay verified. New/changed Core selections affect future schematic claims and verifications; don't change a schematic while players are midway through construction.


## Per-feature unlock restrictions (October 2026)

The Mods -> Milestone -> Config home screen now has an **Unlocks** tab.
Choose **+ Add Feature**, give it a unique ID and display name, and set **Unlock after Core** to the desired Core.
Configure both independently:

- **Lock recipes**: YES/NO. When YES, exact recipe IDs under **Recipe IDs** are excluded from the server RecipeManager until the selected Core is fully COMPLETED. Supports vanilla recipes and mod recipe types which use Minecraft's RecipeManager. Client recipe sync is refreshed when the active set changes.
- **Lock machine use**: YES/NO. When YES, right-clicking a block under **Machine block IDs** is cancelled on the server until the Core is completed. Existing placed blocks and inventories are retained.
- Set either, both, or neither. Leaving both OFF lets you prepare a feature definition without activating any locks.

Example config, placed under the \`features\` array in \`cores.json\`:

\`\`\`json
{
  "id": "early_industry",
  "name": "Early Industry",
  "unlock_core": 0,
  "lock_recipes": true,
  "recipes": ["minecraft:chest"],
  "lock_machine_use": true,
  "machine_blocks": ["minecraft:furnace"]
}
\`\`\`

This example locks the chest *recipe* and use of furnace blocks until Core 1 (ID 0) reaches COMPLETED. It does not remove existing chests or furnaces, and other recipes using their products are not automatically blocked.

An empty feature list (\`"features":[]\`) causes no restrictions and is the default on existing installations. Create a backup of your world before changing locks on a live multiplayer server.

When a configured Core becomes COMPLETED, locked recipes are restored for all players. Admin state/contribution resets can re-lock them; \`/milestoneconfig reload\` reapplies edited feature settings. All such features are server-wide.

**Integration scope:** Recipes are filtered from Minecraft's RecipeManager; a mod that performs processing independently from that manager will require a dedicated compatibility integration. Machine use currently means **player right-click of specified blocks**; it does not stop automation, ticking machines, hopper/funnel/item input, commands, or modded menu packets that bypass block interactions. These do not yet have universal enforcement. Test each mod's recipe and machine behavior before relying on it for progression enforcement. This system does not unload/disable actual mods or remove existing items.

The editing screen uses staged data: return from Unlocks to the main configuration page and press Save to commit; Cancel discards all draft changes.

## Searchable ID picker (October 2026)

Click Browse beside supported ID fields instead of typing a registry ID.

- Task Target: browse entity IDs for kill quests, item IDs for submissions, or server advancement IDs.
- Core Manual task IDs: browse built-in and custom task IDs, choose multiple.
- Core Reward items: browse item IDs. New entries default to one item; existing counts are preserved.
- Core Allowed/Blocked biomes: search biomes from the currently joined world's registry.
- Unlock Recipe IDs: search the server recipe catalog, including temporarily locked recipes (operator only).
- Unlock Machine block IDs: browse modded and vanilla block IDs, choose multiple.

Search matches names where available, full resource IDs and mod namespaces such as create:. Click Add shown to select displayed matches in bulk. Item and block multi-pickers also have Collections: these are registry tags expanded into specific selected IDs. Tags are expanded when selected, and do not dynamically update afterward.

All picker changes require Apply and then Save in the main configuration. Manual text entry remains available; title-screen picking does not expose server-specific recipes, advancements or biome registries.

The code builds but still needs visual and multiplayer in-game verification.


## Milestone Shop MVP (October 2026)

**Admin editor:** Mods -> Milestone -> Config -> Shop. This is now a functional editor, not the earlier placeholder.
Set **Credits per contribution** (0-10000, default 10) and add up to 64 offers. Each offer defines:
- Stable offer ID and display name
- Item ID (with Browse searchable picker), number of items (1-64)
- Price in credits (1-1000000)
- Optional required Core: **Available immediately** or **Core 1-32**; the specified Core must be fully COMPLETED

Edit the list with Add Offer / Delete; use Back to return to config and **Save** to commit. Offers are validated against registered item IDs and Core IDs on the server. No offers exist by default so the server doesn't unexpectedly sell items. New offers default to 1 iron ingot for 10 credits; configure these before saving.

**Player shop:** Right-click the **Resonant Exchange block** (the permanent shop block near spawn) to open the shop directly. The Mission Station is for tasks and schematics only. Players see their own credit balance, item pictures, price, and locked/unlocked offers. Select Buy to purchase one offer. The server independently rechecks distance to a real Resonant Exchange, current item/price, personal credit balance and Core completion. The client never chooses its price or creates the item. Purchased items go to the player's inventory; if full, the overflow drops at the player.

**Currency:** Every player's FIRST successful contribution to EACH Core earns the configured number of credits. Credits are personal, persistent per UUID in overworld SavedData (milestone_shop_accounts.dat), and not recalculated when the credit rate changes. Completed contributions from BEFORE installing this update do not automatically backfill credits. A player's wallet cannot go below zero; purchases deduct the offer's server-configured price once.

**Operator testing commands:** /milestoneshop balance displays your wallet. /milestoneshop grant <player> <credits> adds 1-100000 credits as operator or from the server console. This allows checking purchases on a test world without earning new contributions.

Example config (inside cores.json's shop object):
\`\`\`json
"shop": {
  "credits_per_contribution": 10,
  "offers": [
    {
      "id": "iron_bundle",
      "name": "Iron Bundle",
      "item": "minecraft:iron_ingot",
      "count": 8,
      "price": 20,
      "requires_core": -1
    }
  ]
}
\`\`\`

**Planned later:** Limited stock/restocks, per-item purchase limits, item NBT/data components, purchase history, command/service rewards, visual sorting/filtering, and optional shared server credit pool. The first version intentionally uses personal balances and standard items. Server-authoritative logic compiles but still needs live Minecraft purchase and restart testing before production deployment.


### Shop block interaction update (October 2026)
The shop is now accessed by **right-clicking Resonant Exchange**, not by clicking a button on the Mission Station. The shop screen opens from the block interaction on the server. Every shop request and purchase is checked against the Resonant Exchange block and the player's distance (within 8 blocks); a Mission Station is not accepted as a shop location. No shop configs or wallets are migrated or reset by this change.

## Resonant Exchange icon-grid shop (October 2026)

The shop player UI was rebuilt from the provided sketches. Right-click Resonant Exchange to open it. It opens **with no selection**. The top panel shows your personal **Credits** and an actively counting **Resets** timer. On the left, each configured on-sale item is shown as a **vanilla item icon in a slot**, with a gold outline when selected. Scroll over the grid using the mouse wheel or drag the right-side scrollbar. Rare, epic and legendary offers appear below the divider. Up to two reserved offers appear in the top-right header; upcoming items appear in dimmed slots. Click an icon to see its name, amount, optional description, resource ID and price in the details panel on the right, with Buy and Reserve buttons.

**Stock rotation:** The server displays up to 24 offers per two-hour stock cycle, chosen deterministically using a persistent rotation number; catalogs of up to 24 offers all stay available. The timer's next reset is persisted across server restarts. Admins still configure all offers under Mods -> Milestone -> Config -> Shop.

**Reserve:** Clicking Reserve on an unlocked item holds that offer through the NEXT reset, without charging credits. Each player can hold up to two new reservations. Reserved offers appear in two slots in the top-right header even if the next rotation would otherwise hide them. Click Unreserve to release it, or Buy to purchase and clear the reservation. Reservations expire after the second reset, or if their offer is removed from the server catalog. Purchasing checks the wallet, real Resonant Exchange proximity, completed-Core requirements, active stock/reservation status and server-owned offer price.

**Description:** Shop offer configuration now has an optional Description field (max 240 characters), displayed as wrapped text on the details panel. Existing offers without descriptions keep working and show an automatic item/quantity description.

**Important limitations:** Rarity is currently a manually assigned offer label (common/uncommon/rare/epic/legendary), not yet a probability weight for appearing in the rotating stock. Reserving does not deduct credits, guarantee affordability or prevent the same offer being purchased by others: offers currently have unlimited quantities and no shared stock counter. Configurable rotation duration, limited stock, per-user purchase caps and reservation fees are possible later improvements. The code builds but needs in-game visual and interaction testing.

### Shop GUI sharpness and sizing fix (October 2026)

The Resonant Exchange shop window has been reduced from a maximum of 515x345 GUI units to 438x292, approximately 15% smaller in each dimension. Item slots were reduced proportionately from 31x31 to 25x25 while Minecraft's normal 16x16 item sprites are rendered at native GUI resolution. The scrolling grid, bottom rare-item strip and top-right reserved slots, selected-item details, price, Buy/Reserve buttons, and credits/reset header are preserved.

To prevent the blur reported on the custom shop screen, rendering order was adjusted: vanilla Screen.render finishes first, then the shop draws its own opaque panel, borders, item icons and text, and finally the widget buttons are rendered on top. This keeps custom pixels out of the screen's background-blur pass. A Minecraft client restart is required to load the rebuilt JAR; inspect in-game for visual correctness at your GUI scale.

### Compact, searchable Shop config and sharp admin backgrounds (October 2026)

**Shop catalog:** Mods -> Milestone -> Config -> Shop now has a dedicated search box. It filters existing offers as you type by display name, offer ID, item ID or rarity (case-insensitive). Search does not mutate or remove any offers; editing/deleting works on the underlying offer even when the list is filtered.

The Shop catalog uses tighter 23-pixel offer rows, with a taller scrollable list that extends close to the bottom of the window. Approximately ten offers are visible at once at the maximum GUI dimensions (rather than six). The persistent bottom "Back to Config, then Save..." instruction and redundant footer status area were removed. The top rows separately show navigation, credits per contribution, search and Add Offer. The offer editor fits all eight fields in a compact row list.

**Auto item display names:** New shop offers default to the Minecraft item display name (Iron Ingot). Selecting a new item with Browse or entering a complete registered item ID automatically fills the offer's editable display name with that item's localized name. You can manually replace that display name afterward in its separate field. Existing saved custom names are not modified until you choose a new item.

**Sharp backgrounds:** The main Milestone config, ID picker, Unlocks editor and Shop editor now let the vanilla Screen finish its blurred-background stage before drawing a fully opaque dark background and the custom panels, labels and buttons. No blurry world is visible behind these administration screens. The Resonant Exchange shop already uses that draw ordering.

Menu changes are staged until Save in the parent config screen. Restart Minecraft after the new JAR is installed and verify the appearance at your GUI scale.

### Searchable ID picker compact layout (October 2026)

The ID browser now places **Apply** in the top toolbar alongside Cancel and collection controls. Three redundant footer labels have been removed, and the result viewport extends into their former area: up to 11 visible rows at maximum menu size rather than 8. Each item result now shows the Minecraft item sprite at the left edge, and block results show their associated block-item sprite when available. Search, selection, smooth scrolling and the draggable scrollbar remain available.

### Item icons throughout Milestone menus (October 2026)

Registered Minecraft item sprites appear beside item references in the Shop Offers admin list, the shop offer's Display name and Item ID rows, item-based task list entries and task targets, Core reward item settings, and the Mission Station's item-offering task. The Unlocks editor shows a corresponding block-item icon for machine block references when an item form exists. The searchable ID picker already displays item/block icons. All these icons are read from the installed registries, including modded items; unknown or non-item references remain text-only instead of displaying a misleading icon. Icons move with the rows in smooth-scrolling menus and do not change saved config data.

### Five rarity shop sections (October 2026)

The Resonant Exchange merchandise area now has five separate labeled horizontal item rows: **Common, Uncommon, Rare, Epic, Legendary**. Offers use the Rarity selection configured under Mods -> Milestone -> Config -> Shop; existing offers with no rarity default to Common. Each row shows its own available stock first, then greyed-out preview items from the next rotation, followed by empty slot outlines. The entire five-row area is vertically scrollable with easing and a draggable scrollbar, while holding **Shift and using the mouse wheel** over a rarity row scrolls that row sideways if there are more items than fit. A small Shift+wheel hint appears on overflowing rows. Selected items still show their details and Buy/Reserve buttons; up to two reserved offers remain in the top-right. No wallet, offer or Core data migration is needed.

### Shop stock rarity caps and timed shuffles (October 2026)

In Mods -> Milestone -> Config -> Shop, click Stock Limits beside Credits per contribution. Adjust each rarity's maximum stock, 0-64, with a numeric field or +/- buttons; zero hides that rarity from the current rotation (existing reserved stock can remain). Defaults: Common 6, Uncommon 5, Rare 4, Epic 3, Legendary 2. Return to the main config page and press Save.

The settings live in the shop's max_per_rarity object in cores.json, with common/uncommon/rare/epic/legendary keys.

Each two-hour stock reset makes a new deterministic server-wide shuffle separately for each rarity, selects up to that rarity's configured maximum, and changes item order even when all candidate items fit. Because reset cycle state is saved, all players see the same stock, and restarting the game does not reroll. The Refresh button ONLY reloads the current lineup; it does not force a new rotation. Future previews only include items in the next reset that are not already for sale. Previously reserved offers remain honored until expiry/purchase, even after reducing a rarity cap.

Visuals: Each rarity's label is separated from the items by a high-contrast dark band. Details show rarity rather than raw technical registry ID; the top header uses shorter labels to prevent crowding. All offers, credits and Core progress remain intact.

### Fixed rarity slots, full shop catalog and one purchase per reset (October 2026)

Resonant Exchange now shows fixed slot counts for Common, Uncommon, Rare, Epic and Legendary that exactly reflect Shop -> Stock Limits. Each section expands into multiple physical rows when its configured maximum exceeds the width. Categories have no horizontal scroll; only the overall shop list can scroll vertically. Empty slots remain neutral and no upcoming/future items are greyed out on the selling page.

A new All Items button at the shop footer opens a searchable, read-only list of every configured offer, including items not currently stocked and those locked behind future Core completion. The catalog shows the item icon, name, rarity, price, required Core, completed/unlocked status and current stock status. The catalog cannot trigger purchases.

Purchasing an offer marks that offer as **bought for that player until the next two-hour rotation**. Its icon greys out and displays an X, and Buy and Reserve become disabled. The server enforces a one-purchase-per-offer-per-player-per-rotation rule; reopening the GUI or restarting the server cannot bypass it. Per-player purchased IDs are persisted with shop stock SavedData and cleared when the shop cycle advances. Other players are not affected by one player's purchase. The All Items details also report a purchase already made this rotation.

### Operator-only shop reroll (October 2026)

In the Resonant Exchange, **Reroll Shop** appears next to **All Items** only for
players with server operator permission level 2 or higher. The client receives
this permission flag in the shop snapshot; the server independently checks OP
status and that the player is near a valid Resonant Exchange block. Players
cannot force a reroll by sending a forged packet.

Pressing Reroll Shop immediately advances the same **server-wide shop stock
cycle** used by the timed two-hour refresh. Offers within each rarity are
shuffled against the configured Stock Limits, each player's bought-this-cycle
flags clear, and the next automatic reset timer starts again at two hours.
Existing credits, core progress, offer definitions and valid Held reservations
are preserved. Everyone with an open shop gets their own updated server
snapshot immediately. Regular **Refresh** only reloads data; it never rerolls.


### Iron's Spells rarity text colors (October 2026)

Shop rarity names match the exact Iron's Spells 'n Spellbooks 3.16.3
SpellRarity ChatFormatting values: Common gray (#AAAAAA),
Uncommon green (#55FF55), Rare aqua (#55FFFF),
Epic light purple (#FF55FF), Legendary gold (#FFAA00).
Applied to rarity section headers, selected item details, All Items catalog
rows and detail pane, Stock Limits, and the offer rarity edit control.
Dark backgrounds preserve contrast. No extra runtime mod dependency.

### Mission Station operator debugging (October 2026)

**Debug** appears under **Config** in the Mission Station menu only for OPs with
Minecraft permission level 2 or higher. It opens an in-place panel while keeping
the Mission Station's container and selected discovered Core open. Normal
players cannot see it. Every debug inventory-button action is checked AGAIN on
the server for OP level 2, a currently open/valid Mission Station, and a valid
selected saved Core; merely forging a button click does not grant permission.

The debugging controls apply to the **selected discovered Core**:

- **Core Report** — sends detailed saved state to the OP's chat: Core UUID,
  tier, dimension and location, progress state, schematic verification,
  prerequisite completion, contributor count, assigned task and progress,
  and available task-pool size. Read only.
- **Check Adv.** — for an advancement objective, prints its exact registry ID,
  whether it exists on the server, whether the OP has already earned it, and
  whether the corresponding Mission Station task is complete. Read only.
- **New Task** — replaces only the OP's assigned objective for this Core, using
  a different eligible task if possible, resetting their contribution status.
  If no task can be assigned, the original assignment is preserved. Requires
  Core prerequisites and verified construction.
- **Reset Task** — resets the OP's current task progress (keeps its task ID)
  and removes their Core contribution; requires verified construction and
  prerequisites.
- **Finish Task** — simulates completing the OP's current objective through
  normal contribution logic. This can give the OP normal rewards, shop credits
  and progress towards global Core unlocks. Requires verified construction
  and prerequisites.
- **+1 Contributor** — adds a simulated contributor to the selected Core.
  The normal threshold/unlock code runs; rewards/unlock commands may trigger.
- **Clear Core Progress** — removes all players' contribution/assignment
  progress at that Core. Needs TWO clicks within 10 seconds, confirmed on the
  server. Previously awarded global unlocks and items are NOT automatically
  revoked. Use only in a disposable test world or after backing up.

Messages from these controls start with [Milestone Debug]. Use the Back button
to return to regular station tasks without closing the GUI.

### Resonant Exchange layout improvements (October 2026)

The customer shop grid uses eight item columns at the standard shop size
instead of arbitrarily limiting stock rows to six. Rarity stock limits and
server-side shuffled offers are unchanged. The fixed stock grid and its
rarity headings continue to share one vertically scrolling viewport.
The scrollbar is now attached to the left side of the stock slots, rather
than the far right of the item detail pane; wheel and drag both work there.
The header has darker Credits, Next reset, and Held text strips. The
selected item's detail panel uses a high-contrast dark title bar, a pale
description area with near-black text, and a dark price footer.
