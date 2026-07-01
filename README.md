# DefCoreLib

Shared core library for **def** Paper plugins: data-driven custom blocks built on display entities, plus recipes, state machines, rotation networks, and multi-block mechanisms.

## Features

- **Custom blocks** — defined in YAML, rendered with display entities (orientation-aware, animated, per-state light & particles).
- **State machines** — blocks hold states with transitions triggered by interaction, redstone, or physics.
- **Recipes** — shaped, shapeless, and stonecutter recipes (with a GUI for custom-head inputs).
- **Rotation network** — redstone-driven rotation power distribution.
- **Mechanisms** — moveable multi-block structures (doors, drawbridges, rotators, mechanism minecarts).
- **Glue** — anchor-owned block selection for assembling multi-block structures.

## Requirements

- Java 21
- Paper 1.21.8 (`api-version: 1.21`)

## Build

```sh
./gradlew shadowJar
```

Produces an uber-JAR in `build/libs/`. Drop it into your server's `plugins/` directory.

## Usage from another plugin

1. Add the DefCoreLib JAR as a `compileOnly` dependency.
2. Declare `softdepend: [DefCoreLib]` in your `plugin.yml`.
3. Access registries via `CoreLibPlugin.getInstance()`.

Admin commands: `/defcorelib <give|give_demo|list|colliders|reloadbanners|cleanorphans>` (permission `corelib.admin`).

## Block definitions

Blocks and mechanisms are declared in YAML under `src/main/resources/` (e.g. `demo-blocks.yml`, `rotation-blocks.yml`, `slabs.yml`). See `docs/` for the generated block catalog.
