# sxlxs420's VibeCoded Respawn Anchor Breaker (Fabric, Minecraft Java 26.3)

Client-side mod. While enabled it finds the closest respawn anchor within the radius (default 10 or 4 i forgot),
smoothly turns the camera to it, and holds the normal attack key only while the game's own crosshair
target is that anchor. When it breaks, it moves on to the next closest one.

It does NOT move/teleport you, change reach/attack speed/velocity, place blocks, use items,
or break anything other than respawn anchors. You still have to walk within normal reach yourself or have baritone do it for you.

## Build
Requires JDK 25.
    gradle wrapper --gradle-version 9.6.0   # once, if you have Gradle installed
    ./gradlew build                          # Windows: gradlew.bat build
Jar: build/libs/respawn-anchor-breaker-1.0.0.jar  (ignore the -sources jar)

Or push this folder to a GitHub repo: the included workflow builds the jar and uploads it
(Actions tab > latest run > Artifacts).

## Install
1. Install Fabric Loader 0.19.5+ for Minecraft 26.3.
2. Put Fabric API (0.161.0+26.3 or newer for 26.3) and the mod jar in .minecraft/mods.
3. Launch with the Fabric profile.

## Use / config
- Keybind: R (Controls > Misc) toggles it on/off.
- Config: .minecraft/config/respawn_anchor_breaker.json  ->  enabled, radius (1-32), scanIntervalTicks.
  Edit while the game is closed.
- Pauses automatically when a GUI is open, while using an item, or in spectator mode.

## Supported
Minecraft Java 26.3, Fabric Loader 0.19.5+, Fabric API. Client-side only (safe for donut smp as of 11/15/2026).
Forge/NeoForge/Quilt: not supported.
Note: many multiplayer servers prohibit automation like this. Be cafeful.

also compatable with temple clients legit autototem (when pops opens inv)

(and yes even 99% of the readme is ai dont flame me the mod works)

P.S use code wks for 15% off at https://temple.nz/ best client ever and w devs
