# AynThor Second Screen — Forge 1.20.1 Port Plan

## Objective
Port the Fabric 1.21.11 `AynThorSecondScreen` client mod to **Minecraft Forge 1.20.1** (Forge 47.x), enabling dual-screen HUD streaming for major 1.20.1 Forge modpacks such as **DeceasedCraft** on the AYN Thor.

---

## 1. Architecture & Protocol Compatibility

The wire protocol remains 100% identical and backward-compatible with both the Android companion app and the native Linux companion client (`thor_mc_hud.py`):

* **Transport:** TCP loopback `127.0.0.1:48291`
* **Outbound (Mod → Client):** Newline-delimited JSON state snapshots per client tick (`health`, `maxHealth`, `armor`, `food`, `air`, `xpLevel`, `xpProgress`, `selectedSlot`, `hotbar`).
* **Inbound (Client → Mod):** Slot selection (`"1"`–`"9"`), keybinding simulated presses (`"E"`, `"Q"`, `"F"`, etc.), `"ICON:<id>"`, `"CHAT:<msg>"`.

---

## 2. Fabric 1.21 vs. Forge 1.20.1 API Mapping

| Component | Fabric 1.21.11 Implementation | Forge 1.20.1 Mapping |
|---|---|---|
| **Entry Point** | `net.fabricmc.api.ClientModInitializer` | `@Mod("aynthor_secondscreen")` class with `MinecraftForge.EVENT_BUS.register(this)` |
| **Client Tick** | `ClientTickEvents.END_CLIENT_TICK` | `@SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent event)` with `event.phase == Phase.END` |
| **HUD Suppression** | `HudElementRegistry.removeElement()` (Fabric API) | `@SubscribeEvent public void onRenderOverlay(RenderGuiOverlayEvent.Pre event)` checking against `VanillaGuiOverlay.HOTBAR.id()`, `VanillaGuiOverlay.PLAYER_HEALTH.id()`, etc., calling `event.setCanceled(true)` |
| **Keybindings** | `KeyBindingAccessor` mixin for `timesPressed` | Access transformer or Forge accessor mixin on `KeyMapping` / `KeyBinding.timesPressed` |
| **Chat Capture** | `ClientReceiveMessageEvents.CHAT` | `@SubscribeEvent public void onClientChatReceived(ClientChatReceivedEvent event)` |
| **Java Toolchain** | Java 21 | Java 17 (Forge 1.20.1 standard JVM) |
| **Networking** | `HudStateServer.java` (Pure Java) | Identical (Zero dependencies, drop-in replacement) |

---

## 3. Implementation Steps

1. **Gradle Build Configuration:**
   * Multi-loader setup or standalone Forge submodule targeting ForgeGradle `6.0.+`, Minecraft `1.20.1`, Forge `47.3.0`+.
   * JVM target: Java 17.

2. **Event Handlers:**
   * `ThorHudForgeClient.java`: Registers listeners for `ClientTickEvent`, `RenderGuiOverlayEvent.Pre`, and `ClientChatReceivedEvent`.
   * Wire `HudStateServer` lifecycle to client lifecycle (`ClientPlayerNetworkEvent.LoggingIn` to start, `LoggingOut` to pause/drain).

3. **Validation & Testing:**
   * Test against `hud_sim.py` and native Linux client `thor_mc_hud.py` on loopback.
   * Verify DeceasedCraft 5.10.17 Forge 1.20.1 world load on AYN Thor.
