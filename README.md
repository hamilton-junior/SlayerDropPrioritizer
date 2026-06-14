# Slayer Drop Prioritizer

A RuneLite plugin that makes looting during Slayer tasks faster and cleaner by reorganizing the
right‑click "Take" menu around the drops that actually matter for your current task.

It automatically detects your Slayer task, resolves the monster's drop table from the
[OSRS Wiki](https://oldschool.runescape.wiki/), and then **prioritizes, deprioritizes, or hides**
ground‑item menu entries accordingly — so the loot you want is always at the top, and the clutter
is out of the way.

> The plugin only reorders/relabels ground‑item menu entries. It never adds entries that send
> actions to the server, and it leaves "Walk here", "Cancel", NPC options, and entries created by
> other plugins untouched.

---

## Features

### Smart prioritization

- Detects your current Slayer task automatically (via the official Slayer plugin).
- Resolves the monster's drop table from the OSRS Wiki, including herbs, the Rare Drop Table, the
  Gem Drop Table, and other sub‑tables (parsed from the rendered drop tables).
- Caches resolved tables on disk so the Wiki isn't queried every session.

### Three display modes

| Mode | Behavior |
|------|----------|
| **Show** | No reordering — only optional cosmetic decorations (marker/value/rarity/highlight). |
| **Deprioritize** | Task drops move to the top of the menu; everything else sinks to the bottom. |
| **Hide** | Non‑priority ground items are removed from the menu. |
| **Hide (Take only)** | Removes only the *Take* of non‑priority items, keeping their *Examine*. |

### Flexible "what counts as priority"

- All task drops, or only "interesting" ones (by value and/or rarity).
- Minimum value threshold (GE, High Alchemy, or the highest of both).
- Rarity tiers from the Wiki drop rate (Rare / Ultra‑Rare), with configurable thresholds.
- Optionally prioritize untradeables (imbue scrolls, totem pieces, etc. that have no GE price).
- Custom **Always Priority** / **Always Ignore** item lists.
- Clue scrolls handled separately (always show / follow mode / never; place before or after task drops).

### In‑menu decorations

- A customizable **marker** text/symbol before priority items, with an optional custom color.
- Item **value** and **rarity** shown next to the name, colored using the official Ground Items
  plugin's value‑tier colors by default (or your own custom colors).
- **Highlight** priority items by recoloring the option, the name, or both — with optional tiered
  colors (Ultra‑Rare / Rare / Valuable / common).

### Quality of life

- Right‑click **Prioritize / Ignore** options on ground items to manage your custom lists in‑game
  (mutually exclusive — an item is never both).
- Optional **notification** when a priority drop spawns, filterable by rarity tier.
- Combat timeout in seconds (or keep active until the task ends).
- Optional debug overlay.

---

## How it works

1. When you fight a monster matching your Slayer task, the plugin looks it up on the OSRS Wiki by
   NPC id and downloads the rendered drop table.
2. Item names and drop rates are parsed and stored (in memory and on disk under
   `.runelite/slayer-drop-prioritizer/`).
3. When you open a ground‑item menu within the combat window, the plugin classifies each item and
   reorders / hides / decorates the menu according to your settings.

Menu changes only apply while you're on task and within the combat timeout, so normal looting
elsewhere is unaffected.

---

## Configuration

Settings are grouped into collapsible sections:

- **General** — display mode, examine handling, combat timeout (seconds; `0` = until task ends).
- **Prioritization** — task‑drop scope, minimum value + value source, "interesting drops only",
  prioritize untradeables.
- **Drop Rarity** — treat rare drops as priority; Rare and Ultra‑Rare thresholds (`1/N`).
- **Clue Scrolls** — show behavior and placement of clue scrolls.
- **Custom Lists** — Always Priority / Always Ignore lists, and the right‑click manage option.
- **Menu Text** — marker text/color, value display, rarity display, and value/rarity text colors
  (Ground Items colors by default).
- **Highlight** — recolor priority items (option/name/both), examine handling, base color, and
  tiered colors.
- **Notifications** — priority‑drop notifications and the minimum tier that triggers them.
- **Compatibility & Debug** — collapsed‑items support, disk cache, cache reset, test mode, overlay.

---

## Compatibility

Designed to coexist with common looting plugins:

- **Ground Items** — value/rarity text reuses its value‑tier colors; its decorations on item names
  (stack counts, value suffixes) are handled when normalizing names.
- **Ground Loot Icons** — the item icon is kept first in the entry, with the priority marker placed
  right after it (no overlap or gap).
- **Menu Entry Swapper** & **Loot Tracker** — untouched; the plugin only reorders/relabels
  ground‑item entries and never removes unrelated entries.

---

## Installation

Once approved on the RuneLite Plugin Hub: open RuneLite → **Configuration** → **Plugin Hub** →
search for **Slayer Drop Prioritizer** → **Install**.

### Build from source

```bash
git clone https://github.com/hamilton-junior/SlayerDropPrioritizer.git
cd SlayerDropPrioritizer
./gradlew build       # compile + run tests
./gradlew run         # launch a development RuneLite client with the plugin
```

Requires JDK 11. To log into a development client, follow RuneLite's
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) guide.

---

## Notes & limitations

- Drop data comes from the OSRS Wiki. If a page's drop‑table layout changes, parsing for that
  monster may need an update (the cache version is bumped when the parser changes).
- Disk‑cached tables expire after 7 days; use **Clear Cache & Refresh** to force a re‑fetch.
- The config side panel does not refresh live, so changes made via the right‑click **Prioritize /
  Ignore** options are confirmed in the game chat; reopen the panel to see the updated lists.

---

## Changelog

### v1.0.0 — Initial release

**Added**

- Three display modes — Show, Deprioritize, Hide — plus a Hide (Take only) variant.
- Automatic Slayer task detection and drop‑table resolution from the OSRS Wiki, with on‑disk
  caching and a manual cache reset.
- Prioritization by task membership, minimum value (GE / High Alchemy / highest of both), and
  Wiki rarity tiers (Rare / Ultra‑Rare with configurable thresholds).
- "Interesting drops only" and "prioritize untradeables" options.
- Custom Always Priority / Always Ignore lists, plus right‑click **Prioritize / Ignore** options
  to manage them in‑game (mutually exclusive, with in‑chat confirmation).
- Clue scroll controls (show behavior + placement before/after task drops).
- In‑menu decorations: customizable marker (text + optional color), item value (with `gp` unit),
  and item rarity — with value/rarity text colored from the Ground Items plugin by default.
- Highlight priority items by recoloring the option, name, or both; optional tiered colors and an
  independent "highlight examine" toggle.
- Priority‑drop notifications, filterable by rarity tier.
- Combat timeout in seconds (or until the task ends) and an optional debug overlay.
- Compatibility handling for Ground Items, Ground Loot Icons, Menu Entry Swapper, and Loot Tracker.

**Fixed**

- Drop tables are parsed from the rendered Wiki HTML, so herbs, the Rare Drop Table, the Gem Drop
  Table, and other macro sub‑tables are now captured (previously missing from raw wikitext).
- Item‑name normalization keeps parentheses that are part of the real name (e.g.
  "Sawmill coupon (oak plank)"), stripping only Ground Items quantity/value suffixes.
- Corrected the rarity comparison direction so rarer drops are the ones prioritized.
- Wiki/cache work moved off the client thread for thread safety.

## License

Released under the [BSD 2‑Clause License](LICENSE).

Drop data is sourced from the [OSRS Wiki](https://oldschool.runescape.wiki/), licensed under
CC BY‑NC‑SA 3.0.
