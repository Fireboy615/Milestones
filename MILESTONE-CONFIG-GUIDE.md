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

### Multi-currency costs and global/individual unlocks (October 2026)

**Mission Station > Config > Shop Offers > Edit Offer > Costs & Unlocks**
opens the item-payment and unlock-condition editors (operator-only through
the existing server-validated Config system). Existing offers without these
fields behave exactly as before.

Each offer accepts **up to 8 item payments** (on top of its existing required
credit price) and **up to 8 unlock objectives**. The item payment editor
accepts item registry IDs and required counts from 1 to 2304. Each item ID
must be unique per offer; costs are counted and deducted from the buyer's
normal inventory (main 27 slots and 9 hotbar slots). All payments are checked
first, then committed on the server. A missing item/credit requirement blocks
purchase and consumes nothing.

Each unlock objective has:
- **Type:** kill, structure, or advancement
- **Target:** full registry ID such as minecraft:wither,
  minecraft:ancient_city, or minecraft:story/mine_diamond
- **Count:** kill objectives may require 1-100000 kills;
  structure and advancement objectives use 1
- **Scope:** individual (each player earns it) or global
  (the first eligible player to meet the condition unlocks it for everyone)

Multiple objectives on an offer are joined by **AND**, and may mix scopes.
A separate existing requires_core prerequisite still applies globally.
Example offer configuration:

    "cost_items": [
      { "item": "minecraft:diamond", "count": 4 }
    ],
    "unlock_conditions": [
      { "type": "kill", "target": "minecraft:wither", "count": 1, "scope": "global" },
      { "type": "structure", "target": "minecraft:ancient_city", "count": 1, "scope": "individual" },
      { "type": "kill", "target": "minecraft:enderman", "count": 5, "scope": "individual" }
    ]

Unlock progress is stored in world SavedData named milestone_shop_unlocks,
not attached to the two-hour shop stock. It persists after restarts and stock
rerolls. Both individual and server-wide progress are tracked only for
conditions configured in the active shop. Kill credit is awarded to the player
responsible for the living entity's death. Structure credit requires the player
to be inside an actual generated structure's bounds; detection runs every
5 seconds while a player is in an already-loaded chunk (no locate command or
new world generation). A player's already-earned vanilla advancement also
satisfies newly configured individual advancement objectives. New global
advancement objectives require an event after that objective is configured.

Buyer-facing Resonant Exchange and All Items show item costs, number held,
unlock progress and scope. The Buy button is disabled if requirements are
unmet. The server independently rechecks everything on each purchase.
Hover over an item icon for the **complete list** of conditions and costs.

**World/backups:** Do not delete milestone_shop_unlocks.dat unless you want
to reset global and individual unlock progress. Editing or removing an offer
does not delete recorded progress for conditions which still use the same
type, target and scope. Mis-typed structure/advancement registry IDs can
prevent unlocking; use full valid IDs from installed datapacks/mods.

### Player Intel — approximate player location reports (October 2026)

The **Resonant Exchange > Player Intel** button opens a searchable list of
players who are currently online or were previously recorded by the Milestone
mod. The list contains **only names, UUIDs and online/offline status**.
Choose a player and buy a location report for shop credits.

* A player who's online is located using their current server position at
  purchase time. An offline player's position comes from the last location
  recorded while they were online (updated every 10 seconds and on logout).
  Offline players first become available after the mod has observed them.
* A purchased report is delivered **only to the buyer's chat**, showing the
  player's name, online/current or offline/last-known status, dimension,
  and an approximate X/Z waypoint **within 100 horizontal blocks** of the
  actual current/recorded location. The Y coordinate is never included.
  Offline reports include an ISO-8601 timestamp of the last recorded position.
* The coordinates are picked randomly between about 55 and 90 blocks from
  the real position when a new estimate is required. One stable estimate
  is saved per target and shared across **all buyers and world restarts**.
  Subsequent purchases reuse that estimate so long as it remains within
  100 blocks of the target; this avoids trivial triangulation from
  repeatedly purchasing fresh estimates for a stationary player.
* The exact last-known positions and stable approximations are stored
  server-side in world SavedData (milestone_shop_player_intel). The network
  never sends an exact position to clients, and no position is sent just
  to open the player list. Buyer UUID and the shop block's proximity are
  validated on the server for every purchase.
* Report purchases charge only shop credits, are separate from the rotating
  offer stock, and do not affect normal item purchases or one-per-cycle limits.
  A failed purchase does not deduct credits. Player names are not sent to
  chat globally. Reports do not teleport, track live movement, or reveal Y.

**Default price:** 100 shop credits per report. OPs can change it via
**Mission Station > Config > Shop Offers > Stock Limits > Player Intel price**.
Valid range 0-1,000,000; **0 disables the Player Intel button**.
The setting is saved as shop.player_intel_price in the existing Cores JSON.
Older shop configs automatically receive the default when parsed.

Example shop setting:

    "shop": {
      "player_intel_price": 100,
      "credits_per_contribution": 10,
      "offers": []
    }

**Testing:** Use two test players (one online, then offline), check
the reported point remains within 100 blocks of the true X/Z, verify
purchasing repeatedly while stationary yields the same estimate,
test insufficient funds, config price 0 and cross-restart persistence.

### Selling real Create schematics in the Resonant Exchange (October 2026)

**First import the schematic:** Mission Station > Config > Schematics > Import
from Create. Place the desired compressed .nbt structure in the installed
Create schematic folder and use the existing import workflow. It copies
validated schematics to config/milestone/schematics and registers an ID and
display name in the Milestone config.

**Then add a shop offer:** Mission Station > Config > Shop Offers > + Add Offer
(or select an existing offer). In Edit Shop Offer:
1. Change **Reward type** from Item Stack to Filled Create Schematic.
2. Click **Create Schematic** to cycle through schematics registered under
   the main Schematics page; it initially selects the first if available.
3. Set price, rarity, description, required Core, item payments and unlocks.
4. Save the config through the parent configuration screen.

Schematic offers are normal rotating stock, supporting reservations,
per-player purchase limits, shop rerolls and all existing unlock and
item/credit payment rules. The reward item icon uses Create's actual
schematic item, with an **S** in the item slot, and the full catalog labels it.
One purchase yields ONE filled schematic; quantity cannot be increased.
Existing item offers are unchanged (missing reward_type defaults to item).

When purchasing, the server first checks the registered schematic ID and
revalidates the safe compressed .nbt file (same file validation as the main
Milestone schematic library: at most 262144 compressed bytes, dimensions at
most 24 on each axis, no block-entity or entity contents and no forbidden
blocks). If preparation fails, **no credits or inventory payment items are
consumed**. Once the payment succeeds, Create's filled schematic item is
given to the buyer, and its exact .nbt data is sent privately to them.

Purchased templates live under Create's uploaded schematics directory, in
a dedicated milestone-shop owner folder. Content-hashed filenames protect
previous purchases if an OP later edits an original .nbt. Players carrying
or receiving a purchased schematic have its client template synced at login
and periodically when their inventory changes (including traded items).
The entire shop blueprint library is NEVER sent to all connecting players.
The previous Core/Mission Station schematic features are unaffected.

JSON structure example within an offer:
    "reward_type": "schematic",
    "schematic": "my_cottage",
    "count": 1

Known limitation: This currently uses the existing registered Milestone
schematic library and its conservative 24-block-side/no-block-entity limits.
It does not directly sell arbitrary unregistered files from a player's PC.

### Existing Loot Pool Editor (October 2026) — locally compiled, NOT installed

Navigate to **Mission Station > Config > Loot Tables**, select any loaded
loot table, then press **Edit Existing Pools / Restore**.

The new pool editor lists actual entries within the selected pool (six per
page) and supports navigation across pools. The UI shows the pool rolls,
total entries and whether Milestone's world datapack has a staged override.

For a **direct item entry** (type minecraft:item), operators may:
- Stage a new relative **weight** from 1 to 1000; this does not represent
  a standalone percentage, because pool rolls, conditions and competing
  entries determine the actual outcome.
- Stage a **count** from 1 to 64, optionally with min/max for a uniformly
  distributed stack count. Only entries with absent or exactly one simple
  existing minecraft:set_count function are accepted. Unrecognized or
  conditional count functions are left read-only for count editing.
- **Remove** the direct item entry if the pool has at least one other entry.
- **Restore Original Table**, which removes only Milestone's override and
  exposes the underlying datapack's original table after /reload.

Nested alternatives, groups, tags, dynamic entries and arbitrary modded
loot functions remain visible and are NOT edited by this version. All
unknown JSON fields, existing pool conditions, non-count entry functions
and other entries are preserved. Each operation requires two clicks to
confirm before any server-side filesystem change. The server revalidates
the selected table, entry indexes, OP permission, numeric limits and the
SHA-256 revision of the currently staged table to prevent stale writes.
Only server-generated world datapack paths are writable; raw client JSON
and arbitrary absolute paths are not accepted.

Edits stage a world datapack override inside
<world>/datapacks/milestone_loot_editor, preserve an on-disk backup of
previous staged overrides in <world>/milestone-loot-backups and use an
atomic file move when supported. The modded/vanilla source JAR remains
untouched. **Run /datapack enable "file/milestone_loot_editor"** if the
pack is not enabled and then **/reload** after editing. Only future loot
generation uses the new results. Lootr per-player inventories previously
generated are left intact.

Limitations: This is not yet a general visual editor for nested items,
weighted probabilities across conditional pools or table-level functions.
The preview simulator and statistical reverse matching of ordinary
already-opened chests remain future work. The editor is compiled locally
using Gradle check/jar; it is not automatically deployed to Internshit
Server until you explicitly ask.

### Loot browser categories, icon lists & scrolling (October 2026)

Mission Station > Config > Loot Tables now defaults to **Chests** instead
of mixing chest contents with every block's drops. The filter tabs are
Chests, Blocks, Mobs (entity loot), Fishing, Other, and All. Searching
only includes the selected category. Categories are evaluated on the
server from conventional loot-table registry paths and, for unusually
named modded loot tables, the top-level loot-table type field. Some
nonstandard scripted mod loot may appear under Other; All is an escape
hatch. Clicking a chest in the world still identifies its Lootr or
vanilla source table regardless of the current filter.

Each page shows its filter, total matching count, and a scroll track.
The track can be clicked/dragged and the mouse wheel scrolls while over
the table list. Buttons display a representative category item icon,
with the full modded registry ID available on hover.

After selecting a table, the right side shows the explicit item entries
using their actual Minecraft inventory icons and localized item names.
Hover for the full registry ID; use its independent scrollbar/mouse wheel
to browse longer item lists. The UI currently previews at most 96 distinct
direct item IDs; indirect tag or nested dynamic rewards may require
opening the pools themselves and are not guaranteed to be exhaustive.

The **Edit Existing Pools / Restore** editor also has item icons beside
selectable direct entries, labels/registry-ID hover text, and a separate
draggable list scrollbar and mouse wheel navigation between pages.
All previously implemented server-side OP checks, editor confirmation,
datapack staging, optional Lootr support, and historical inspection remain.

To avoid unexpectedly changing the modpack, compile locally with
gradlew check jar --offline instead of gradlew build (which runs
the automatic Internshit Server installation task). These changes do
not affect a running world until the user explicitly requests deployment.

### Add new weighted item entries to existing loot pools (October 2026)

This upgrade is **written and compiled in IntelliJ, not deployed to the
Internshit Server modpack** until the user requests installation.

Workflow for operators:
1. Mission Station > Config > Loot Tables; find a table in Chests,
   Blocks, Mobs, Fishing, Other, or All.
2. Select a table and choose **Edit / Add Pool Entries**.
3. Navigate to the loot pool where the new item belongs. Click
   **+ Add Item to This Pool** on the left side.
4. In the dedicated Add Entry screen, use **Choose Item...** for a
   searchable vanilla/modded item picker (with icons), or enter the
   registered item ID manually. Verify the item preview.
5. Set **relative weight (1-1000)** and **minimum/maximum count
   (1-64)**. The weight is relative to other entries in that pool,
   NOT a guaranteed drop percentage. The stack count is a fixed
   number or a uniform inclusive range.
6. Click **Stage New Entry** and click **CONFIRM Add Entry**.
   After the server writes the override, you return to the
   appropriate loot pool page with the new item displayed.
7. Enable the world datapack if not already enabled and run /reload
   for *future* chest/player loot to reflect the new entry.

Unlike **Stage Bonus to Datapack**, which adds a new independent pool,
Add Entry appends a standard minecraft:item entry to an **existing**
pool, inheriting that pool's rolls, conditions, and competition with
other entries. Existing conditions, functions, NBT, and pool metadata
are preserved. An unconditional direct item already in the same
pool is rejected as a duplicate; edit its weight/count instead.
The editor deliberately does not add nested alternative, tag,
dynamic or custom modded entry types yet.

Validation is enforced on the server (OP permission level 2,
loaded loot-table registry ID, registered non-air item ID,
existing target pool, max 256 entries, valid weight/count,
and the expected SHA-256 table revision). Requests do not
allow raw JSON or arbitrary paths. Previous staged overrides
are backed up and output is written to a world datapack.
The original Minecraft/mod JAR is never edited.

### Loot menu layout cleanup (October 2026)

The Loot Tables admin interface has been reorganized as a sequence of
smaller, focused screens, without changing any loot-table permissions,
datapack semantics, or Lootr compatibility.

- **Loot Tables:** Choose Chests/Blocks/Mobs/Fishing/Other/All, search
  the server's table IDs, inspect an item-icon preview and scroll it.
  The bottom actions are now only **+ Bonus Pool** and **Edit Pools**.
  Keeping numeric drop editing off this page leaves more room to browse.
- **Loot Pools:** Navigate between pools and pages; rows show icons,
  human-readable item names, relative weights or a Complex label.
  Clicking a row selects it. Use **+ Add Item**, **Edit Selected**, or
  **Restore Original Table**. The scrollbar, keyboard/mouse wheel and
  page controls adjust to available GUI height (2-6 visible rows).
- **Edit Selected:** Dedicated form to change an existing simple item's
  relative weight or stack count, or remove the entry. All actions
  require confirmation and preserve unrelated conditions/functions.
  Complex entries stay read-only where the backend cannot edit safely.
- **Add Item / Bonus Pool:** The same searchable item-picker form now
  handles both workflows. It clearly labels the difference between an
  item competing in an existing pool (relative weight 1-1000) and a
  separate bonus pool (percentage chance 1-100). Both allow a stack
  range from 1 to 64 and require confirmation before writing.

This resolves collisions between previews and edit fields at smaller GUI
scales, removes overlapping edit actions from pool lists, and repairs
scrollbar dragging to the final partially filled page. A GUI scale that
produces a very tiny available window may still need adjustment in
Minecraft's Video Settings.

Built locally with Gradle check/jar only. Do not use Gradle build to
test without intending to install, because this project's build task
automatically copies the JAR to Internshit Server.

### Editing fixed enchantments in loot entries (October 2026)

New optional editor (compiled locally; not automatically deployed):

1. Open Mission Station > Config > Loot Tables.
2. Choose the chest/block/entity loot table, then **Edit Pools**.
3. Select a **direct item** entry, click **Edit Selected** and
   **Enchantments...**.
4. Left column shows that entry's fixed enchants/levels, if any.
   Right column searches ALL synced vanilla + modded enchantment registry
   entries by display name or namespace ID; it supports paging and wheel
   scrolling. Pick one.
5. Enter a new level, then click **Set / Add Enchant** twice to
   confirm, or **Remove Enchant** twice to remove an existing one.
6. The server writes a staged world datapack override. Use /reload
   after enabling the datapack as needed; only future loot rolls
   change, including future per-player Lootr rolls.

The editor uses 1.21.1's minecraft:set_enchantments function with
one fixed integer level per enchantment, preserving unrelated NBT/data
components, item functions, pool rolls, item drop weights and conditions.
Enchanted books also use this vanilla loot function.

The server enforces permission level >=2, registered enchant IDs,
normal enchantment max level (not deliberately overlevelled values),
a maximum of 24 fixed enchants per item entry, and the same SHA-256
table revision check used for all other editor operations. Existing
no-namespace ID spelling is preserved. Deleting the last fixed enchant
removes the empty set_enchantments function.

Random or conditional enchantment functions (enchant_randomly,
enchant_with_levels, conditional/nonconstant set_enchantments,
enchant-related set_components, plus enchantment-generating pool/table
functions) are intentionally shown as **read-only** here. This prevents
silently discarding RNG behavior or modded function settings. A
separate advanced random-enchantment editor may be developed later.
Custom modded reward functions that generate enchantments indirectly
may not be recognized by this simple inspector.

This version is NOT installed into a modpack by local Gradle check/jar;
the user's installed Internshit Server milestone JAR is unchanged
until explicitly requested.

### Read-only Enchantment Rules inspector (October 18, 2026)

Mission Station > Config > Loot Tables > select any chest, block, mob,
fishing, or modded loot table > **Enchant Rules**.

This NEW menu is separate from **Edit Pools > Edit Selected >
Enchantments...**, which continues to add/remove fixed enchantment
values. Enchant Rules is a *read-only source inspector* and cannot
generate loot, open containers or change the world.

The server reads the original/staged loot-table JSON and searches for
enchantment-related functions applied to:
- the whole table,
- each loot pool,
- items/entries nested inside groups, alternatives and sequences.

Left column: paginated list of functions with a scope such as
"Pool 2 > Entry 4 (minecraft:enchanted_book)".
Right column: registered function ID, a human-readable summary where
possible, and the function's exact source JSON. Both list pages and
right-hand JSON details support scrolling. Lists adapt to GUI size.

Recognizes vanilla fixed minecraft:set_enchantments, random
minecraft:enchant_randomly and minecraft:enchant_with_levels,
other minecraft:enchant_* functions, and enchantment-related
minecraft:set_components. It shows configured fixed levels,
level providers, option lists, and function conditions *as present
in JSON*. Modded function settings are displayed verbatim where the
viewer cannot interpret them safely.

Read-only inspection is also available for custom or conditional
entries; those rules remain non-editable in the fixed enchantment
editor. The inspector does not predict exact enchantment probabilities,
follow referenced nested loot-table IDs, or execute script-based modded
loot generators. For unusually large modded function JSON, the preview
is truncated at 2400 characters per rule, with a visible notice.
A maximum of 1000 functions is scanned and displayed per table.

No JAR is installed into Internshit Server by the local compile or jar
task. Deploy only after the user explicitly requests installation.

### Edit enchantment rules in existing loot tables (October 18, 2026)

Navigate to **Mission Station > Config > Loot Tables > Enchant Rules**,
choose a rule, and click **Edit Rule**. This editing control is available
to server operators and is separate from Edit Pools > Edit Selected >
Enchantments, which modifies fixed enchants only on one direct item.

Supported safe edits:
- **minecraft:enchant_randomly**: restrict eligible enchantments to a
  registered enchantment ID, multiple comma-separated enchantment IDs,
  or an enchantment tag prefixed with #; blank removes the restriction.
  Toggle **Compatible Only** (on/off). Minecraft still chooses which
  enchantment and level to apply during each future loot roll.
- **minecraft:enchant_with_levels**: change the allowed enchantment IDs
  or tag, and the enchanting power between 1 and 100, either fixed
  (min=max) or a uniform min-max provider. These are enchanting power
  levels, *not* the guaranteed level of each resulting enchantment.
- **minecraft:set_enchantments**: choose a registered vanilla/modded
  enchantment using the searchable registry picker or type its ID,
  set its fixed level (capped at the registry's normal maximum), or
  remove it from that rule. Deleting the last one removes the empty
  enchantment function.

The inspector's rule list identifies the item/pool/table location,
function type, and source JSON; **Edit Rule** edits that exact original
function. The server accepts only a small, validated set of typed fields,
not arbitrary raw JSON or filesystem paths. Each operation requires a
second click to confirm. Server rechecks OP permissions, SHA-256 table
revision, path, function index and type, enchant IDs/tags and numeric
limits before writing a staged world datapack override. Existing
conditions, pool rolls, weights and unrelated functions remain unchanged.
The datapack writer backs up previous staged overrides.

Unsupported modded functions, complex enchantment options, and custom
or non-uniform enchantment level providers remain viewable as source
JSON but are read-only. Conditional functions preserve conditions
exactly; there is no separate editor for condition probabilities yet.
After staging, use /reload (and enable the Milestone world datapack if
needed). Changes affect future loot only, not already rolled Lootr
inventories or previously opened vanilla chests.

Development status: local Java compilation/check/JAR; no new JAR will
be installed into Internshit Server until the user explicitly asks.
