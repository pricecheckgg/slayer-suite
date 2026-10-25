<h1 align="center"><img src="icon.png" width="32" height="32" alt=""> Slayer Suite</h1>

<p align="center">The whole slayer loop in one RuneLite plugin. One card, one panel, one alert at a time.</p>

<!-- Screenshots live in docs/: task-card.png, task-complete.png, panel-task.png, panel-loot.png, hover.png -->

Slayer Suite reads the client, draws, and tells you things. It never attacks, prays, eats, drinks, moves or clicks for you, and it shows nothing the game hides. It needs RuneLite's own Slayer plugin enabled.

## What you get

| Part | What it does |
|---|---|
| **Task card** | Kills left with a bar, ETA with the finish time, kills per hour, points and streak as tiles. The prayer to use with its icon, the style, the superior, the finishing item. Where you are with cannon, multi and safespot chips, or the pinned spot with the next step to reach it. A gear checklist with have, bring or missing beside each item. |
| **Prayer icons** | The protection prayer each task monster calls for, drawn above its name while that prayer is off, on every task monster in range so you see it before you attack. Monsters that switch styles show both icons with the active one dimmed. |
| **Hover card** | Hover a task monster: HP, max hit and attack style tiles, the prayer with ON or OFF, weakness, Slayer xp, the finishing item, and poison or cannon warnings. Solid, on top of everything, out of the way of the game's own hover text. |
| **Spots for your account** | Every location in the dataset is scored against your quests, diaries, levels, spellbook and the teleports you actually own, including the furniture in your house. The best one is pinned on the world map, an arrow on the minimap points at it with the distance, a floor path leads you through the loaded area, and the panel lists every way there marked carried, in bank, not owned or needs a spellbook swap. The Shortest Path plugin is optional: when it is installed the pin becomes a door-to-door route; without it you still get the arrow, the floor path and the steps. |
| **Readiness check** | At the bank: the slayer helmet or the task's item, the finishing item, dragonfire or poison protection, food, prayer restores, cannonballs. Required in red, recommended in yellow, each marked when it is sitting in the bank you have open. Leaving with something required missing raises one alert. |
| **Alerts** | One prioritised alert at a time instead of three plugins beeping over each other: low Hitpoints, venom, poison without a cure, low prayer, a boost wearing off, attacking the wrong thing, missing items, cannon empty or broken, the aggression timer, standing idle in a task area, supplies running out early, nearly done, superior, task done. Banner, RuneLite notification per severity, chat line, sound, a pause hotkey. |
| **Task complete** | A centred card when the assignment ends: kills, time, xp, loot, points and streak count up, with the drops you picked up as item tiles under them and the ones you left behind dimmer below. The last few kills count down on screen. A level up gets the same card with the skill icon and everything slain and picked up since that skill's previous level. |
| **Attack guard** | Attack on monsters that do not count for your task is pushed off left-click or hidden. |
| **Loot tab** | Your Slayer level with the xp bar to the next one, virtual levels past 99 and a maxed state at 200m. This task's drops. This level's drops. One card per level reached with its time, kills, loot and what was left on the floor, every older level a click away. Recent drops overall. |
| **History** | Per task, on your RuneScape profile: kills, time actually spent killing, Slayer xp, loot value, points per task, skips and blocks. The chat tells you your own rates when a task is assigned and says when one is well below your median, with the skip cost against your balance. |
| **Slaying mode** | Everything switches on while you have a task and are in its area, fought a task monster in the last five minutes, or carry a slayer helmet, and goes quiet otherwise. You never turn the plugin off to do something else. |

## Install

From the Plugin Hub: search for Slayer Suite.

From source, for sideloading in developer mode:

```
JAVA_HOME=/path/to/jdk11 ./gradlew jar
cp build/libs/slayer-suite-*.jar ~/.runelite/sideloaded-plugins/
```

`./gradlew sideload` does the copy for you.

## How it keeps the numbers honest

**Task state** comes from the game's own tables, so the task is known at login with no gem check, for every master including Mortimer.

**A drop counts as yours** only when the loot log attributed it to a task monster, the task's kill counter moved within four seconds of that death, and the item then appeared in your inventory within ninety seconds while you stood within ten tiles of where it fell, on the same plane. A confirmed drop that never reached your inventory before it expired is recorded as left behind. Grimy herbs into a carried herb sack and seeds into a seed box never touch the inventory, so those count as picked up when you step onto the drop.

**Points** earned per task and the streak come from the game. Points leaving the balance are classified from the drop that precedes a task change: 30 is a skip, 100 a block, anything else a shop purchase. A task that vanishes with kills left, nothing paid and the streak intact went into task storage, and its kills, xp and loot are kept so it resumes where it left off.

**The supply forecast** classifies what you carry: prayer restores become prayer points at your level, antifires become minutes, food and other potions are counted per kill. Antifire only counts on tasks that call for it. The forecast speaks up only when you will run short.

## Settings

Nine sections, one per thing you see: Task card, Prayer icons and tooltips, Readiness check, Alerts, Task complete, Sounds, Attack guard, History and advice, Spots and travel. Slaying mode and the overlapping-plugins notice sit above them. When another enabled plugin does a job this one does (Slayer Task ETA, Scape Target, TaskJingle, Item Reminders, AFKing Toolkit alerts) a checklist at the top of the screen names each one and clears itself as you switch them off.

## Try the displays

Type `::ss done`, `::ss superior`, `::ss level`, `::ss alert`, `::ss supplies` or `::ss sound` in the chat box, or press the buttons at the bottom of the panel, to fire the task complete card, the superior card, the level up card, a critical alert, the supplies alert or the task-done sound. Test events are not recorded in your history. `::ss vars` prints which game values the task is being read from.

## Sounds

A chime when the task ends, an 8-bit encounter jingle when a superior spawns, a beep for critical alerts, a tick on the last kills. All of them are synthesised by `tools/make-sounds.py`; nothing is sampled from any game. Drop your own `task-done.wav`, `superior.wav`, `alert.wav` or `level-up.wav` into `.runelite/slayer-suite/sounds/` to replace them.

## The dataset

`src/main/resources/com/slayersuite/data/monsters.json` holds one record per assignment: names and aliases, the NPC ids that count, every variant with its combat level, Slayer experience, Hitpoints, attack styles and poison flags, the superiors, every location with its map region ids and whether it is multicombat, cannonable or safespottable, the master ranges, requirements, and the hand-maintained item rules, prayer and style.

It is built by `tools/build-dataset.py` from the Old School RuneScape Wiki:

```
python3 tools/build-dataset.py            # uses tools/cache, downloads only what is missing
python3 tools/build-dataset.py --refresh  # re-download everything
```

The script reads every `Slayer task/<name>` page, follows each variant to its monster page for the infobox facts, falls back to the monster page for core RuneLite task names that have no task page, and merges `tools/overrides.json`. It writes `monsters.json`, `DATA-ATTRIBUTION.md` (every page and revision used, with the licence) and `tools/report.txt` (coverage gaps).

Three hand-maintained files hold what the wiki does not state in a machine-readable way:

- `tools/overrides.json`: required items, finishing items, protection, prayer, style and notes per task, each entry dated once it has been checked against the wiki prose. `python3 tools/check-overrides.py <task-key>` prints the rules next to the wiki sentences they came from.
- `src/main/resources/com/slayersuite/data/travel.json`: per location, the quest or diary needed to get in and the teleports that reach it, with how far you still run afterwards.
- `src/main/resources/com/slayersuite/data/items.json`: the item groups those rules refer to and the item names that satisfy them. Matching is by normalised name, so charges, doses and imbues do not matter.

`tools/runelite-task-names.txt` lists the task and NPC names as the core Slayer plugin spells them; `tools/dump-core-task-names.sh` regenerates it from the client jar.

## Code map

| Package | Role |
|---|---|
| `task` | `TaskTracker` reads the task from the game's tables and the core service, counts kills, detects completion, points earned and spent, and stored tasks. `GameTaskTable` is the table lookup. `SlayerMode` decides when the plugin is awake. |
| `data` | `SlayerDataset` loads the dataset and answers lookups by any spelling of a task name and by map region. |
| `card` | The task card, the prayer icons, the hover card, the task choice helper and the task complete and level up card. |
| `readiness` | `ReadinessChecker` scans equipment, inventory and the open bank; `ReadinessOverlay` draws the bank list. |
| `alerts` | `AlertKind` is the priority table, `AlertEngine` evaluates the sources and fans out to the banner, the notifier, chat and sounds. |
| `guard` | `AttackGuard`, the menu-entry filter. |
| `advisor` | `LocationScorer` ranks spots through `AccountState`; `Advisor` keeps the pin, the next step and the travel options; `GpsOverlay` and `PathOverlay` draw the arrow and the floor path; `HouseScanner` remembers your furniture; `SlayerSuitePanel` is the side panel. |
| `history` | `TaskHistory` keeps per-task stats, the assignment totals, the loot ledger and level records; `PickupMatcher` turns inventory gains into confirmed drops. |
| `trip` | `TripTracker` measures consumption per kill and projects kills until dry. |
| `sounds` | `SoundPlayer`, bundled sounds with per-file overrides. |

## Credits

- **RuneLite** and its core Slayer plugin (BSD 2-Clause). This plugin depends on it for the live target list, spells task and NPC names the way it does (`tools/runelite-task-names.txt`), uses the same item ids for task icons (`tools/task-icons.txt`), and reads the game's slayer tables the way the core plugin does. Both files are regenerated from the client jar by `tools/dump-core-task-names.sh`; no RuneLite source is copied.
- **Old School RuneScape Wiki** contributors, for every monster, location, requirement and drop fact in the dataset (CC BY-NC-SA 3.0, see `DATA-ATTRIBUTION.md`).
- **Shortest Path** by Skretzo, whose plugin message API this plugin hands the pinned spot to when it is installed.
- The slayer plugins this one grew out of using side by side: Slayer Task ETA, Slayer Sensei, Slayer Item Reminders, Slayer Scape Target, TaskJingle and AFKing Toolkit. None of their code or assets is included; the overlap notice names them so you can switch them off.
- Prayer and skill icons and item sprites are the game's own, drawn at runtime through RuneLite's sprite and item managers; nothing of Jagex's is shipped in the jar. The sounds are synthesised by `tools/make-sounds.py`; the plugin icon is drawn by hand.

Old School RuneScape and RuneScape are trademarks of Jagex Ltd. This project is not affiliated with Jagex or RuneLite.

## Licence

The code is BSD 2-Clause (see `LICENSE`). The dataset is derived from the Old School RuneScape Wiki under CC BY-NC-SA 3.0; every page and revision used is listed in `DATA-ATTRIBUTION.md`.

## Roadmap

1. **Block advice.** Skip advice exists; block advice needs assignment weights per master, which the dataset does not carry yet.
2. **Travel coverage.** `travel.json` has 45 locations; spots without an entry rank on their flags alone. Portal nexus destinations are not readable yet, so a nexus counts for its Xeric and Digsite additions alone.
3. **Live style switching.** Monsters that alternate styles show both prayer icons; knowing the current style before the hit lands needs per-monster animation tables, starting with hydras.
4. **Looting bag.** Drops picked up into a looting bag never show in the inventory, so they read as left behind.
5. **Dataset in CI**, so wiki changes become a rebuild and a diff.
6. **Dataset gaps.** 75 of 1116 location rows have no map pins, a few boss and umbrella pages have no record, and 33 of the 149 task entries have been hand-reviewed so far. `tools/report.txt` lists them.
7. **Inventory Setups hand-off**: select the matching setup when that plugin is installed.
