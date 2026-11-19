# sxlxs420's VibeCoded Respawn Anchor Breaker  (Fabric, Minecraft Java 26.3)
uses Temple Client's Baritone  ·  discord.gg/templ  ·  Temple Client: https://temple.nz  (code `wks` for 15% off)

Client-side mod that runs the whole respawn-anchor grind on its own: it finds anchors, looks at them and mines them with
the normal attack key, collects the drops, eats, refills your hotbar, deposits into an ender chest, and tells Baritone
where to go next. It is built to keep going for hours: when something goes wrong it recovers (restarts Baritone, RTPs)
and writes down what happened in an in-game activity log.

> Yes, most of this readme (and the code) is AI. The mod works. Many servers ban automation like this: the author reports
> using it on Donut SMP as of 11/15/2026, but that is not an official statement. Check the rules of any server you play on.

## Controls
| Key | What it does |
|---|---|
| Right Ctrl | open the menu (Esc or Right Ctrl closes it) |
| R | master on/off |
| (unbound by default) | one optional key per module: bind it in the module's settings page |

All keys can also be changed in Controls > Misc. Everything pauses by itself while a menu is open, while you use an item
(except when the mod is eating or using the trident itself), in spectator mode, or when you ride something.

## The menu
Dark gold "liquid glass" panels (the blur comes from the game's menu background).
* **Left-click** a module = turn it on/off. **Right-click** (or middle-click) = its own settings page: sliders, toggles, text
  boxes (click the number next to a slider to type one in; click outside to apply, Esc cancels) and its keybind.
* Categories: **Mining**, **Support**, **Navigation**, **Overlays**, plus the **Activity Log** panel (mouse wheel scrolls, Clear erases).
* Every overlay can be turned off/on and **dragged** by its body while the menu is open. Positions are saved.
* Everything is saved in `config/respawn_anchor_breaker.json` and survives restarts.

## Overlays
* **Credit card**: Anchor Addon, GitHub sxlxs420, Temple Client + Baritone, temple.nz code wks 15% off, discord.gg/templ.
* **Market**: respawn anchor price, trend arrows, 5 min / 1 h / 24 h change, 24 h low/high with a buy/sell hint, volume if the
  site reports it, "updated Ns ago" and a status badge (LIVE / CACHED / OFFLINE / NO DATA).
* **Farm Stats**: what it is doing right now, anchors mined, average per hour, last 10 min / 1 h rate, anchors carried and stored.
* **Keybinds list**: appears once any module has a key assigned.
* **Log overlay**: the latest log lines on screen (off by default).

### Where the market data comes from (read this)
donut.auction's own page says its public order API was retired; the site's data comes from a JSON backend at
`https://api.donut.auction` (documented only by third parties, not by donut.auction). The mod calls
`/v2/items/search?q=respawn_anchor` in the background (never on the game thread), at most once every 60 s by default
(30 s minimum), backs off after failures, and caches the last good answer.
* The response format is not documented, so the parser is defensive: it looks for the respawn anchor entry and a price field
  and shows which field it used. If it can't find one, the overlay says so (with the field names it saw) instead of guessing.
* Trends are computed from the samples the mod itself records (saved in `config/respawn_anchor_breaker_market.json`), so
  nothing is invented. Until it has collected an hour of history, the 1 h change shows `n/a`. The 24 h range needs a few samples.
* A third-party price tracker reported that donut.auction's "market value" can be higher than what items actually sell for,
  so treat it as an estimate rather than a guaranteed sale price.
* If the endpoint or format changes, edit `marketUrl` / `marketQuery` in the config; no rebuild needed. Please keep the
  "Data: donut.auction" credit visible (the overlay does).

## How the automation fits together (priority order, every tick)
1. Totem pop handling, inventory refill, RTP in progress, ender-chest transfer (they own the screen/inventory).
2. Baritone chat reports and delayed restarts; RTP triggers.
3. Safety: swim up when low on air; health guard.
4. Hunger: eat before you can no longer sprint.
5. Collect dropped anchors (a cluster is cleared first).
6. Eating (after anchors, hunger, healing).
7. Hotbar refills (food, cobblestone).
8. Deposit into the ender chest.
9. Rain trident.
10. Mine anchors in reach; otherwise search, swim, or send Baritone.

One piece of code decides all Baritone commands (`#goto` / `#cancel`), rate-limited, and nothing sends one during an RTP.
Slow checks (stall detection, flood watch) only run when nothing time-critical is happening, so they never interrupt mining.

## Modules and their settings
**Mining**
* **Anchor Mining** (master): *aim distance* (farthest anchor it will aim at; lower it if it stares at ones it can't reach), scan interval.
  It drops any anchor it can't hit within 10 s or that stays out of range.
* **Cluster Mining**: clears every anchor within *cluster radius* before collecting any drops, and eating waits too. Gives up waiting after a minute.
* **Pickaxe Swap**: pickaxe slot and *swap cooldown* (20 ticks = once a second). It only swaps when aimed at an anchor in reach, and
  cancels Baritone first so Baritone placing blocks and the pickaxe swap can't fight each other. If something keeps changing
  the slot anyway, swaps pause for 5 s and it's logged.
* **Clear Snow**: mines a snow layer that covers an anchor (the only non-anchor block it mines for you besides its own ender chest).
* **Auto Pickup**: walks to drops; *max drop* controls how far a fall it accepts.
* **Water Mining**: when an anchor next to water is within *water range*, it cancels Baritone, swims there itself (sprint-swim along the
  look direction, hops when stuck), mines it, collects the drops, swims to the nearest solid ground, and only then sends Baritone again.
  It also swims up when air runs low.
* **Unreachable Drops**: if it can't walk straight to a dropped anchor, it asks Baritone to path to that spot (`#goto x y z`), since Baritone can
  place and break blocks. It gives up on that drop after 60 s.

**Support**
* **Food**: food slot; *eat when hungry* with a *hunger threshold* (default 10; sprinting stops at 6) and *eat until* level. When it triggers it
  pauses Baritone, eats, and resumes the previous goto. Also eats after each anchor (*pieces per anchor*), and *refill food* moves the same
  food from the inventory into the food slot when it runs out.
* **Cobble Refill**: when the hotbar has no cobblestone it opens the inventory, swaps a stack in (a free slot, or the slot you choose), checks it
  worked, and closes the inventory, only when nothing else is going on.
* **Deposit Anchors**: at *deposit at* anchors (32-640) it places the hotbar ender chest, shift-clicks anchors in with human-style pauses and
  (optionally) mines the chest back (needs Silk Touch). If it can't place or fill the chest, it skips depositing until the quota has been
  reached a second time, and logs it.
* **Health Guard**: below *low health* (default 5 HP = 2.5 hearts): cancel Baritone, eat until full, wait for full health, resume.
* **Totem Guard**: after a pop it opens the inventory after a pause and waits until a totem has been in the offhand for a full second with nothing on
  the cursor before closing (timeout configurable). Needs an AutoTotem that works with the inventory open.

**Navigation**
* **Baritone Goto**: the goto and cancel commands. (Without Baritone these would be posted in server chat: turn this off.)
* **Stuck Recovery**: if Baritone makes no progress (no 3-block move and no nearer anchor) for *stall time* (default 90 s) it restarts the goto.
  Real progress resets the timer. After *max restarts* in 10 minutes it escalates to an RTP instead of looping.
* **Auto RTP**: triggers are: no anchors in render distance for *no-anchor time* (60 s), Baritone reporting no anchors/paths in chat,
  another player within *player distance* (100; only counts players in the tab list), Flood Avoider, and *Maximise profit*
  (nearest anchor farther than *far distance* with no 2 clusters of 3+ and no 3 clusters of 2+, true for a full minute, not while
  the rain trident can cover it). One RTP at a time, a cooldown (45 s), and an hourly cap (20). It waits for the teleport to actually
  happen (you move 32+ blocks or the world changes; 30 s timeout), then resets anchor detection and sends Baritone again.
  If your server's /rtp opens a menu, set *menu slot* to click it.
* **Flood Avoider**: Baritone pillaring up over a wall looks like rising about a block at a time in the same column while standing on the
  ground. After *sensitivity* steps it cancels Baritone and RTPs (or just restarts the goto). Swimming, going around a wall, stairs, ladders
  and ordinary bridging don't count.
* **Rain Trident**: in rain, with a Riptide trident in the trident slot, it makes a *trip* to the closest known anchor (anywhere in render
  distance up to *max distance*): it launches, and when the flight ends it lines up and launches again straight away, in the air, with no
  landing or new search in between, until an anchor is in mining reach (then normal mining takes over). Each launch is planned so the
  whole player-sized lane is clear (feet, waist, head, shoulders), not just the line of sight: if the straight line is blocked it picks the
  best clear lane, sideways or upward (into the sky first, then down onto the target). A lane that ended in a block is remembered and not
  repeated, a launch is cancelled (by switching items, never by firing it) if the lane gets blocked while charging, and the trip is dropped
  after 5 launches without getting closer or 3 blocked ones in a row. It checks the landing for lava and big drops, never launches
  on low health, and cancels Baritone first. *Lane length* is how far ahead the lane must be clear: set it a little longer than one flight.

**Overlays**: toggles and positions (market: refresh time and what to show).

## The activity log
Press Right Ctrl to see it (or turn on the Log overlay). Every line has a time and a tag such as `[Anchor] [Mining] [Navigation] [Food] [RTP]
[Flood Avoider] [Inventory] [Water] [Trident] [Market]`. Warnings and important events can also be mirrored into chat (*Also in chat*).
Identical lines collapse ("x3"), per-tick situations are rate-limited, and if the same thing keeps happening a `[Loop]` warning
appears. Typical things to look for: repeated "Skipping this anchor", "No progress detected", "Hotbar slot keeps changing".

## Requirements and install
Minecraft Java 26.3, Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3, Java 25. Fabric only; client-side only.
Put the mod jar (not the `-sources` jar) and Fabric API in `.minecraft/mods`. Compatible with Temple Client's legit AutoTotem
(the inventory opens on a pop).

## Build
JDK 25, `gradle wrapper --gradle-version 9.6.0` once, then `./gradlew build` (Windows: `gradlew.bat build`).
Jar: `build/libs/respawn-anchor-breaker-2.0.0.jar`. Or push the folder to GitHub: the included workflow builds it and uploads the jar
(Actions tab > latest run > Artifacts).

## If a server join crashes or the mouse isn't captured
Version 2.0.0 had a bug: it registered its module keybinds with a raw `-1` key, which Minecraft 26.3 saved into `options.txt`
and then read out of range (crash with `IndexOutOfBoundsException` on joining a server, odd "network protocol error", and the mouse not
being captured after closing a menu). This build registers them as properly unbound and resets any bad saved value at startup.
If you still crash on launch, close the game, open `.minecraft/options.txt` and delete every line that contains
`respawn_anchor_breaker.module`.

## Known limits
* It was written without being able to run Minecraft: the first run in-game is the real test. Report what the log says.
* No pathfinding of its own on land: it walks straight at drops and asks Baritone for anything harder.
* Underwater mining and swimming are simple (straight-line swimming with hops); very twisty underwater terrain may need Baritone.
* Chat-based failure detection reads Baritone's messages from the chat window using reflection; if a Minecraft update changes that, it
  turns itself off (the stall detector still works) and logs it.
* RTP assumes `/rtp` works on your server (or a menu you configure).

P.S. use code **wks** for 15% off at https://temple.nz/ best client ever and w devs
