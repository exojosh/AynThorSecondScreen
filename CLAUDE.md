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
- `HudState` — record of one tick's snapshot; static `hotbarFromInventory(player)` builder. Carries `air`/`maxAir` (raw ticks and the current maximum, which Respiration raises above 300) for the companion app's bubble row — sent raw rather than pre-reduced to a bubble count, so the app applies vanilla's own rounding and no HUD decision gets baked into the wire format. This postdates `thor-hud-handoff.md` §2's field list.
- `HudStateServer` — owns the `ServerSocket`, one accept + one reader thread per client, `broadcast()`/`broadcastIcon()`/`broadcastAsset()`/`sendAssetTo()`/`pollCommand()`/`pollNewClient()`. Writes are synchronized per-socket because the asset bundle is written to one client in a loop while `broadcast()` may be writing a state line to the same socket.
- `HudAssetCatalog` — **the app gets all its HUD textures from here**, pushed over the socket when it connects (`pollNewClient()` → `sendAssetTo()` in the tick loop; must be on the client thread because the resource manager is client-thread state). The app used to require users to hand-copy extracted textures into its assets folder; serving them means a fresh install just works, resource pack overrides come through for free (the resource manager already resolves the pack stack), and no Mojang assets sit in the app's repo. The wire format uses short stable keys (`heart_full`), not paths, so a version bump that moves sprites is a one-file change here and the app never knows. Paths verified against the 1.21.11 jar — note vanilla nests hearts under `heart/` but keeps armor and food flat with an underscore (`hud/armor_full.png`); that inconsistency is real, not a typo.
- `CommandDispatcher` — maps letter codes to `KeyBinding`s and digit strings to hotbar slots. Hotbar taps go through the vanilla `hotbarKeys[]` binding rather than setting the inventory slot directly, so `MinecraftClient.handleInputEvents` keeps handling spectator mode, the creative toolbar modifiers, and server sync.
  - **Simulating a press needs both of a KeyBinding's states.** `isPressed()` reads a public `pressed` flag; `wasPressed()` drains a *separate, private* `timesPressed` counter that only the static `onKeyPressed` hook normally increments. Nearly every discrete vanilla action — hotbar selection, inventory, drop, swap-hands — reads `wasPressed()` and ignores the flag entirely, so the original `setPressed(true)`-only approach silently did nothing for any of them. `KeyBindingAccessor` (mixin) exposes the counter; `pressForOneTick` bumps it *and* sets the flag for one tick. Don't "simplify" this back to one or the other.
  - Vanilla only calls `handleInputEvents()` when `currentScreen == null && overlay == null`, so commands are **dropped** while a screen is open. Letting them queue instead would stack unconsumed presses that all fire at once when the inventory closes.
  - Timing: Fabric's `END_CLIENT_TICK` runs *after* that tick's `handleInputEvents()`, so a dispatched command is picked up on the next tick (~50ms). `tick()` must run before `dispatch()` each tick, which places the release after the game has read the flag.
- `ItemIconRenderer` — **the primary icon path.** Renders an item/block offscreen through the real vanilla model pipeline and reads it back as PNG bytes, so icons are isometric (vanilla's GUI display transform), use correct per-face textures, and work for composited/tinted/3D-modelled items. See "Offscreen icon rendering" below for how and why.
- `ItemIconResolver` — legacy fallback only, used when `ItemIconRenderer` produces nothing. Resolves an item's raw texture PNG via `MinecraftClient`'s resource manager, with an `item/` → `block/` texture-path fallback. Structurally can't handle items whose icon isn't a single same-named texture file (`jungle_stairs` → `jungle_planks`, `stripped_birch_wood` → `stripped_birch_log`, multi-face logs, etc.), which is exactly what it used to render blank.
- `NativeImageInvoker` (in `com.exojosh.client.mixin`) — `@Invoker` exposing `NativeImage`'s private `write(WritableByteChannel)` so icons can be PNG-encoded in memory instead of via a temp file. Registered in `aynthor_secondscreen_v1_21.client.mixins.json`.

### Offscreen icon rendering

Verified against decompiled 1.21.11 (`./gradlew genClientOnlySources`), not recollection. **An earlier note here claimed this was impossible — that was wrong, and the reasoning is worth recording so it isn't re-derived.**

The dead end is real but narrower than it looked: `DrawContext.drawItem()` only records into a `GuiRenderState`, and `GuiRenderer.render()`'s flush is indeed hardwired to `MinecraftClient.getFramebuffer()`. What that analysis missed is that the *draw* path underneath is redirectable. `RenderLayer.draw(BuiltBuffer)` reads two public static fields — `RenderSystem.outputColorTextureOverride` / `outputDepthTextureOverride` — and targets those instead of the layer's own framebuffer when set. `WorldRenderer` uses this, and so does `GuiRenderer.prepareItemElements()`, which renders every GUI item into a private "UI items atlas" texture *before* compositing. So vanilla itself already renders items fully offscreen; `ItemIconRenderer` is that same sequence pointed at our own 64×64 texture:

1. `ItemModelManager.clearAndUpdate(state, stack, ItemDisplayContext.GUI, ...)` — bakes the real model into an `ItemRenderState`. `ItemDisplayContext.GUI` is what carries vanilla's isometric 30°/225° transform; this is the whole reason icons look like vanilla rather than flat textures.
2. Set the two output overrides + an orthographic `ProjectionMatrix2("...", -1000, 1000, true)` sized to the icon, and `DiffuseLighting.Type.ITEMS_3D`/`ITEMS_FLAT` depending on `state.isSideLit()`.
3. `ItemRenderState.render(...)` only *queues* commands into an `OrderedRenderCommandQueueImpl`; drain `getBatchingQueues()` → `getItemCommands()` through `ItemRenderer.renderItem(...)` into our own `VertexConsumerProvider.Immediate`, then `draw()`. (This mirrors `ItemCommandRenderer`, minus the entity-outline pass. Going through the command queue avoids needing accessor mixins for `LayerRenderState`'s private `renderLayer`/`glint` fields.)
4. `CommandEncoder.copyTextureToBuffer(...)` for readback — same pattern as `ScreenshotRecorder`, but keeping alpha rather than forcing it opaque. **This is fenced and therefore asynchronous**, so the API is callback-based, not a return value.

Nothing touches the real window framebuffer, so there's no on-screen flash on the primary display (the flash trade-off the old note described does not apply).

Constraints worth knowing before editing this: must run on the render thread with no render pass open — `END_CLIENT_TICK` satisfies both, which is why icon rendering is driven from `ThorHudClient`'s tick rather than a HUD callback. The GPU texture/projection/allocator are static and reused across icons; that's safe despite async readback because GL orders `copyTextureToBuffer` against preceding draws.

### Icon request reliability

Icon requests are queued (`MAX_ICON_RENDERS_PER_TICK`) rather than rendered inline, and **every request gets an answer**: a rendered icon, a fallback raw texture, or an explicit failure message (`broadcastIconFailure`, which sends `{"type":"icon","itemId":...}` with no `data` field). Silently dropping unresolvable requests was half the cause of icons appearing inconsistently — the companion app's other half is described in its own CLAUDE.md.

The main (non-client) source set (`src/main/java/com/exojosh/`) is untouched Fabric-template boilerplate and not part of the HUD feature.

## Known unverified/fragile areas (per the handoff doc)

- A number of Yarn-mapping API calls (`player.getArmor()`, `getInventory().getSelectedSlot()`, `ItemStack.hasGlint()`, etc.) were written from best recollection during development and individually compile-checked, but not confirmed together in one clean build.
- Package name is settled at `com.exojosh` (main) / `com.exojosh.client` (client), not still in flux as the handoff doc's §3 warns.
