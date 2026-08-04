# Thor HUD Bridge — starter mod

## 1. Install (one time)

- JDK 21 (Eclipse Temurin): https://adoptium.net
- Git
- VS Code extensions: "Extension Pack for Java", "Gradle for Java" (`vscjava.vscode-gradle`)

## 2. Generate the actual project scaffold

Don't hand-write `build.gradle` / the Gradle wrapper — go to
https://fabricmc.net/develop/template, pick a recent Minecraft version (1.21.x)
and Loader, tick "Fabric API," generate, download, unzip, `code .` into it.

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
./gradlew runClient
```

Once the game is running and you've joined a world, from another terminal:

```
nc localhost 48291
```

You should see one line of JSON per tick — health, food, xp, hotbar contents.
That confirms the HUD-hiding + data pipeline both work before you write a
single line of Android code.

## Known unknowns to verify before relying on this

- `HudLayerRegistrationCallback` / `VanillaHudElements` class names are from
  Fabric API's layered-HUD rework and have moved between Fabric API releases.
  Check the Fabric API version the template generator picked against its
  javadoc/source before assuming the constants used here still exist.
- Gson is on the classpath transitively via Minecraft's own dependencies, but
  if you get a `ClassNotFoundException` for `com.google.gson.Gson`, add
  `modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"`
  isn't enough by itself — you may need to explicitly `include`/shade Gson,
  or just write the ~10 lines of manual JSON serialization instead.
