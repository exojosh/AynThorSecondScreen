# AYN Thor Second-Screen Minecraft HUD — Technical Handoff

**Target device:** AYN Thor (dual-screen Android handheld)
**Launcher:** Zalith Launcher 2 (PojavLauncher-based)
**Minecraft:** 1.21.11, Fabric Loader 0.19.3, Fabric API 0.141.5+1.21.11, Yarn mappings 1.21.11+build.6
**Java:** JDK 21 (mod side)

---

## 1. Architecture overview

Two independent processes on the same physical device, talking over a loopback TCP socket:

1. **A Fabric mod** running inside Minecraft's JVM (itself embedded in ZL2's Android process via `JNI_CreateJavaVM`). It hides the vanilla HUD (health, hunger, XP, armor, hotbar) and:
   - Broadcasts a JSON snapshot of that state every client tick
   - Accepts short text commands back (hotbar slot selection, simulated keybind presses, item-icon requests)

2. **A standalone Android companion app**, written in Kotlin/Compose, that:
   - Renders the hidden HUD elements on the Thor's second (bottom) screen via Android's `Presentation` API (`DISPLAY_CATEGORY_PRESENTATION`)
   - Polls the mod's socket, reconnecting automatically if the game isn't running yet or closes
   - Sends input back to the mod (tap-to-select hotbar, a grid and a radial gesture pad for custom keybinds)
   - Resolves HUD icons from the player's active resource pack (via Storage Access Framework), falling back to bundled textures, falling back to simple drawn placeholder shapes

**Why standalone rather than a ZL2 fork:** the `Presentation` API is available to any app, not just whichever process is showing the primary screen, so no launcher-level integration is needed. "Only show the second screen if the mod is running" falls out for free from the socket connection state — no separate mod-detection logic required.

**Why a socket instead of shared memory:** both processes are technically in the same Android device but different app sandboxes (separate Android processes/UIDs), so loopback TCP was the simplest cross-process channel available. Newline-delimited JSON, no framing beyond that.

---

## 2. JSON protocol

**Transport:** TCP, bound to `127.0.0.1` only (never leaves the device), port **48291**.
**Framing:** one JSON object per line, newline-delimited, UTF-8.

### 2.1 Outbound: HUD state (mod → app)

Sent every client tick (~20/sec, matching Minecraft's tick rate — **not throttled**, worth revisiting if this proves wasteful).

```json
{
  "health": 20.0,
  "maxHealth": 20.0,
  "armor": 0,
  "food": 20,
  "xpLevel": 0,
  "xpProgress": 0.14285715,
  "selectedSlot": 0,
  "hotbar": [
    { "itemId": "minecraft:dirt", "count": 1, "damage": 0, "maxDamage": 0, "hasGlint": false },
    { "itemId": "minecraft:air", "count": 0, "damage": 0, "maxDamage": 0, "hasGlint": false }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `health` / `maxHealth` | float | Half-heart units (20.0 = 10 hearts) |
| `armor` | int | Armor points, 0–20 |
| `food` | int | Hunger points, 0–20 |
| `xpLevel` | int | |
| `xpProgress` | float | 0.0–1.0, fraction toward next level |
| `selectedSlot` | int | 0–8, currently active hotbar slot |
| `hotbar` | array[9] | Always exactly 9 entries; empty slots are `"minecraft:air"`, count 0 |
| `hotbar[].itemId` | string | Namespaced ID, e.g. `minecraft:diamond_sword` |
| `hotbar[].count` | int | Stack size |
| `hotbar[].damage` / `maxDamage` | int | Both `0` for non-damageable items — **not** "fully worn," means "no durability bar to draw" |
| `hotbar[].hasGlint` | boolean | Enchantment shimmer flag |

No `"type"` field on this message shape — the app distinguishes it from icon responses by the *absence* of `"type":"icon"`.

### 2.2 Outbound: icon response (mod → app, on-demand only)

Sent only in reply to an icon request, never proactively or periodically.

```json
{ "type": "icon", "itemId": "minecraft:dirt", "data": "<base64-encoded PNG bytes>" }
```

### 2.3 Inbound: commands (app → mod)

Plain text, **not JSON** — one command per line:

| Format | Meaning | Handling |
|---|---|---|
| `"1"`–`"9"` | Select that hotbar slot | Simulates a one-tick press of `options.hotbarKeys[n-1]` |
| Short letter code (e.g. `"E"`, `"R"`, `"G"`, `"H"`, `"K"`) | Simulated keybind press | Looked up in `CommandDispatcher.COMMANDS`, simulates that `KeyBinding` for one tick |
| `"ICON:<itemId>"` | Request an item's icon | Mod resolves and replies asynchronously with a §2.2 message |

Both digit and letter presses release automatically on the *next* tick (`CommandDispatcher.tick()` runs before any new dispatch each tick), so a press always lasts exactly one game tick regardless of how long the command took to arrive.

---

## 3. File / class structure

**⚠ Package naming has changed multiple times during development** (`com.exojosh` → `com.exojosh.client` → project currently appears to be `com.exojosh.minecraftsecondscreen` on the Android side). **Confirm actual current package names in both projects before treating anything below as literal** — the class *responsibilities* are accurate, the package prefixes may not be.

### 3.1 Mod (Fabric, Java)

| File | Responsibility |
|---|---|
| `ThorHudClient.java` | `ClientModInitializer`. Hides HUD elements via `HudElementRegistry`/`VanillaHudElements`. Per-tick: releases prior simulated keypresses, builds `HudState`, broadcasts it, drains the inbound command queue (routing `ICON:` requests separately from action commands). |
| `HudState.java` | Record holding one tick's HUD snapshot (see §2.1). Nested `HotbarSlot` record. Static `hotbarFromInventory(player)` builder. |
| `HudStateServer.java` | Owns the `ServerSocket` (loopback:48291), one accept thread + one reader thread per connected client. `broadcast(HudState)` and `broadcastIcon(itemId, base64)` both route through a shared `send(json)`. `pollCommand()` drains the inbound queue. |
| `CommandDispatcher.java` | `COMMANDS` map (letter code → `KeyBinding`). `tick()`/`dispatch(code)` simulate one-tick presses. Parses digit strings as hotbar-slot selection separately from the letter-code map. |
| `ItemIconResolver.java` | Resolves an item's **raw texture file** via `MinecraftClient`'s resource manager (which already respects the active resource pack stack). Tries `textures/item/<path>.png`, falls back to `textures/block/<path>.png` for plain blocks. |
| `ItemIconRenderer.java` | **⚠ Unfinished, see §5.** Intended to capture the item's *actual rendered* GUI icon (correct for dyed/tinted/glinting items) via an offscreen framebuffer, as a more accurate alternative to `ItemIconResolver`'s raw-file approach. |

### 3.2 Companion app (Android, Kotlin/Compose)

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Detects the Thor's external `Presentation`-category display via `DisplayManager`. Owns the `HudRepository` instance. Shows `SecondScreenPresentation` on the external display if found; otherwise shows minimal status on the primary screen (also useful for dev/testing without the physical device). |
| `SecondScreenPresentation.kt` | `Presentation` subclass hosting a `ComposeView`, with lifecycle/viewmodel/saved-state owners manually wired to the host `ComponentActivity` (required since a `Presentation`'s window isn't part of the normal Activity view hierarchy). |
| `net/HudRepository.kt` | Owns the reconnect loop to `127.0.0.1:48291`. Parses each inbound line, routing by `"type"` field to either HUD-state or icon-response handling. Exposes `hudState`/`isConnected` as `StateFlow`, `iconCache` as a Compose-observable map. `sendCommand()` / `requestIcon()` write back over the same socket. |
| `net/GameDirectoryAccess.kt` | One-time SAF folder-picker flow (`ActivityResultContracts.OpenDocumentTree`) + persisted URI permission, granting read access to ZL2's game directory. |
| `net/ResourcePackIconProvider.kt` | Resolves the fixed set of HUD sprite icons (hearts/armor/food/xp) from the player's active resource pack. Parses `options.txt` for pack order, searches each pack (zip or folder) top-down via the `HudIcon` enum's candidate sprite paths. |
| `net/BundledIconProvider.kt` | Fallback icon set bundled in `app/src/main/assets/`, used when no resource pack overrides a given sprite. |
| `ui/SecondScreenApp.kt` | Top-level tab switcher (HUD / Input) shown inside the `Presentation`. |
| `ui/HudScreen.kt` | Renders armor (top), hearts + hunger (shared row, left/right justified), full-width centered XP bar, hotbar. Includes a tiled dirt-style background. |
| `ui/HotbarRow.kt` | Renders the 9 hotbar slots: item icon (via on-demand request), stack count, durability bar, static enchant-glint overlay, selected-slot highlight, tap-to-select. |
| `ui/InputGridScreen.kt` | 3×3 grid of buttons sending fixed command codes. |
| `ui/RadialInputPad.kt` | Press-anywhere / drag / release gesture control — 8 segments by drag angle, each mapped to a command code. |
| `ui/TiledTextureBackground.kt` | Generic `ShaderBrush`/`BitmapShader`-based repeating-texture background helper. |

---

## 4. What's currently working

- Mod compiles and runs on 1.21.11 / Yarn / Fabric API 0.141.5, vanilla HUD elements successfully hidden
- Socket server confirmed live: JSON streaming verified via manual `nc`/Python listener, correct health/food/xp/hotbar values observed
- Companion app connects, auto-reconnects, and renders armor/hearts/hunger/xp with the full icon fallback chain (resource pack → bundled → drawn shape), laid out to match vanilla's arrangement
- Bidirectional command channel operational: hotbar slot selection (both tap-on-slot and raw digit) and simulated keybind presses confirmed working end-to-end
- Durability bar and (static) enchant-glint rendering implemented on hotbar slots
- Radial 8-direction gesture input control implemented (angle math verified, visual wheel renders)
- Tiled background helper implemented and in use behind the HUD

## 5. Known bugs / unresolved issues

- **`ItemIconRenderer.java` is broken and mid-rewrite.** Original offscreen-framebuffer approach hit a `SimpleFramebuffer` constructor mismatch (expected 4 args, 3 given). Currently being reworked around `DrawContext.drawItem()` — the same call vanilla's own `InGameHud.renderHotbar()` uses — which is the architecturally correct approach, but the rewrite was **not completed or tested** as of this document. `ItemIconResolver` (raw texture file) remains the working fallback.
- **Real item icons not reliably confirmed end-to-end.** The `textures/item/` → `textures/block/` fallback in `ItemIconResolver` was added to fix blocks (e.g. dirt) showing blank, but has not been visually confirmed fixed on-device since the change.
- **Hotbar background pixel alignment unsolved.** If/when switching to vanilla's real `hotbar.png`, naively stretching it behind a `Row` of equal-width Compose children will not match vanilla's actual fixed-pixel per-slot layout or its separate selected-slot overlay sprite. Needs either fixed slot widths matching the source texture or explicit offset-based positioning.
- **A long list of Yarn-mapping guesses throughout the mod are individually unverified**, including but not limited to: `player.getArmor()`, `player.getInventory().selectedSlot`, `ItemStack.hasGlint()`/`isDamageable()`/`getDamage()`/`getMaxDamage()`, `Identifier`'s package location, exact `VanillaHudElements` constant names, `HudElementRegistry.removeElement()`. Most compile-checked individually during development, but a full clean-build pass to confirm all of them together hasn't been done.
- **Package naming inconsistency** across both mod and app from repeated reorganization during development — needs a final consistency pass (see §3 warning).
- No reconnect backoff on the Android side (flat 1.5s retry) — acceptable for development, not hardened.
- No eviction on the Android-side icon cache (`iconCache` in `HudRepository`) — grows unbounded for the life of the app session.
- Enchantment glint is a static translucent gradient, not vanilla's real animated diagonal shimmer — intentional first-pass simplification.

## 6. Next steps

1. Finish and test the `DrawContext.drawItem()`-based rewrite of `ItemIconRenderer`, with `ItemIconResolver` as the confirmed fallback path
2. Visually confirm the item/block texture fallback actually fixes block icons on-device
3. Decide on and lock in final package names for both projects; do a cleanup pass
4. Confirm `RadialInputPad` is actually wired into `SecondScreenApp`'s Input tab (built, integration point not explicitly confirmed)
5. Add reconnect backoff and icon cache eviction before considering this production-ready
6. Do a full clean build + Ctrl+Space verification pass over all flagged uncertain API names in one sitting, rather than the incremental catch-as-caught-fire approach used so far
7. Confirm ZL2's game directory is configured to shared storage (not the default `Android/data/...` path) — required for `GameDirectoryAccess`/`ResourcePackIconProvider` to work at all; Android structurally blocks cross-app access to `Android/data`
8. Decide whether to pursue vanilla-accurate animated enchant glint and pixel-perfect hotbar background, or accept the current simplified versions
9. Consider throttling the HUD broadcast rate if 20/sec proves unnecessary for smooth display
