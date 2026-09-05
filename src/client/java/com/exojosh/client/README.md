# Thor HUD Bridge — client source notes

## 1. Install (one time)

- JDK 25 (Eclipse Temurin): https://adoptium.net
- Git
- VS Code extensions: "Extension Pack for Java", "Gradle for Java" (`vscjava.vscode-gradle`)

## 2. Generate the actual project scaffold

Don't hand-write `build.gradle` / the Gradle wrapper — go to
https://fabricmc.net/develop/template, pick Minecraft 26.2 and the matching
Fabric Loader/API, generate, download, unzip, and `code .` into it.

## 3. Wire in these files

Copy the three `.java` files from `src/client/java/com/exojosh/thorhud/` into
the matching path inside the generated project (adjust the package name if
you picked something other than `com.exojosh.thorhud` in the generator).

In the generated `fabric.mod.json`, add a client entrypoint:

```json
"entrypoints": {
  "client": ["com.exojosh.thorhud.ThorHudClient"]
}
```

## 4. Build and test without any Android code yet

```
sh ./gradlew runClient
```

Once the game is running and you've joined a world, from another terminal:

```
nc localhost 48291
```

You should see a hello line immediately, followed by JSON lines whenever the
HUD state changes — health, food, XP, and hotbar contents.
That confirms the HUD-hiding + data pipeline both work before you write a
single line of Android code.

## Known validation boundaries

- The current 26.2 build uses Fabric's `HudElementRegistry` replacement API
  and official Mojang names, and is covered by `sh ./gradlew clean build`.
- Item icons currently use the active resource-pack's raw item/block texture;
  vanilla-accurate composed, tinted, and 3D item-model rendering remains a
  follow-up for the 26.2 feature-renderer API.
- Authenticated-world and physical AYN Thor/companion-app testing still needs
  the relevant Minecraft account and hardware.
