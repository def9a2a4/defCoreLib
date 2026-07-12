# v0.2.0 Changelog

- per-world management
- better container handling, pick block, and performance
- various minor fixes


Some of these changes inspired by the edits of https://github.com/xiaozhangup/Pipes

## Features

- **Per-world PipeManager** — each world gets its own PipeManager with independent lifecycle; config to enable/disable pipes per world
- **Container adapter system** — new `ContainerAdapter` interface with implementations for furnaces (vanilla hopper parity) and brewing stands (no extraction during brewing)
- **Path caching & sleep/throttle** — cached pipe paths with full-invalidation on topology changes, sleep for idle pipes (empty source / full destination), dead-end recheck cooldown, transfer phase offset to spread load across ticks
- **Creative pick-block** — middle-clicking a pipe in creative mode now gives the correct pipe item (compatible with HeadSmith)
- **CI pipeline** — GitHub Actions build + server startup test matrix across Paper 1.21 through 26.1.2

## Fixes

- **PDC migration** — pipe entity tags migrated from scoreboard tags to PersistentDataContainer with automatic migration on chunk load
- **Stale variant references** — config reload now re-resolves PipeVariant objects; warns if a variant was removed from config
- **Duplicate delete_all message** — fixed double response when running `/pipes delete_all`
- **Recipe unlock listener on reload** — `/pipes reload` now properly re-registers the recipe unlock listener so config changes take effect

## Internal

- Shared adapter helpers (`tryInsertSlot`, `removeFromSlots`) extracted to `ContainerAdapter` interface
- `VariantRegistry` streamified; added `getQueryableManagers()`
- `WorldManager` handles world load/unload lifecycle


