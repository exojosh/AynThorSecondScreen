# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod (Java 21) that runs inside Minecraft's JVM. It hides the vanilla HUD (health, hunger, XP, armor, hotbar) and streams that state every client tick over a loopback TCP socket, so a separate companion Android app can render it on a second screen. It also accepts short text commands back over the same socket (hotbar slot selection, simulated keybind presses, item-icon requests).

This mod is one half of a two-process system targeting the **AYN Thor** (dual-screen Android handheld) running Minecraft via **Zalith Launcher 2**. The other half — the Android companion app that renders the HUD — lives in a sibling repo, `Android_AynThor_MinecraftSecondScreen`. The two communicate only via the socket protocol; there's no shared code or build.

**`thor-hud-handoff.md`** is a detailed technical handoff/design doc covering the full system (both repos) — read it first for the JSON protocol spec, architecture rationale, and known issues. It's a snapshot from a point in development and may drift from the current tree in file/class specifics (there was a period of frequent package renaming) — verify anything file-specific against the actual source before relying on it.

## Commands

```
./gradlew build          # compile + package the mod jar
./gradlew runClient      # launch a dev Minecraft client with the mod loaded
```

Requires JDK 21. Uses Fabric Loom with `splitEnvironmentSourceSets()` enabled, so code lives in either `src/main` (both sides) or `src/client` (client-only — nearly all of this mod's logic lives here, since it's a client-only HUD mod). Versions (Minecraft, Yarn mappings, Loader, Fabric API) are pinned in `gradle.properties` — check there before assuming an API shape, since Yarn mappings have changed across versions used during development and a number of API calls in this codebase were written from best recollection rather than confirmed against the mappings (see `thor-hud-handoff.md` §5).

Manual protocol smoke test (no Android app needed): run `runClient`, join a world, then from another terminal:
```
nc localhost 48291
```
You should see one JSON line per tick.

## Architecture

Full protocol spec (message shapes, field meanings, command codes) is in `thor-hud-handoff.md` §2 — don't re-derive it from source when the doc already has it verified. In short: the mod broadcasts a full HUD snapshot (health/food/xp/armor/hotbar) every client tick (~20/sec, unthrottled) over `127.0.0.1:48291`, newline-delimited JSON. It replies to `ICON:<itemId>` requests with a base64 PNG, and accepts single-tick simulated key/hotbar-press commands as plain text lines.

**Why a socket, not shared memory:** the mod and the companion app run in separate Android app sandboxes (different processes/UIDs) despite being on the same device, so loopback TCP is the simplest channel available.

### Key classes (`src/client/java/com/exojosh/client/`)

- `ThorHudClient` — `ClientModInitializer`; hides vanilla HUD elements via `HudElementRegistry`/`VanillaHudElements`, starts `HudStateServer`, and on each `ClientTickEvents.END_CLIENT_TICK` builds/broadcasts a `HudState` and drains inbound commands (routing `ICON:`-prefixed ones separately from action commands).
- `HudState` — record of one tick's snapshot; static `hotbarFromInventory(player)` builder.
- `HudStateServer` — owns the `ServerSocket`, one accept + one reader thread per client, `broadcast()`/`broadcastIcon()`/`pollCommand()`.
- `CommandDispatcher` — maps letter codes to `KeyBinding`s and digit strings to hotbar slots; simulates one-tick presses, released at the start of the next tick (`tick()` runs before dispatch each tick).
- `ItemIconResolver` — resolves an item's raw texture PNG via `MinecraftClient`'s resource manager (which already respects the active resource pack stack). **Note:** there are currently two near-duplicate copies of this class — `com.exojosh.client.ItemIconResolver` and `com.exojosh.client.mixin.ItemIconResolver` — which have drifted slightly (the `mixin` package one adds an `item/` → `block/` texture-path fallback the other doesn't have). Worth reconciling into one class before extending icon resolution further. The `ItemIconRenderer` class described in the handoff doc's §5 (an unfinished `DrawContext.drawItem()`-based rewrite) does not currently exist in the tree.

The main (non-client) source set (`src/main/java/com/exojosh/`) is untouched Fabric-template boilerplate and not part of the HUD feature.

## Known unverified/fragile areas (per the handoff doc)

- A number of Yarn-mapping API calls (`player.getArmor()`, `getInventory().getSelectedSlot()`, `ItemStack.hasGlint()`, etc.) were written from best recollection during development and individually compile-checked, but not confirmed together in one clean build.
- Package name is settled at `com.exojosh` (main) / `com.exojosh.client` (client), not still in flux as the handoff doc's §3 warns.
