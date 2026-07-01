# Plugin Split: DefCoreLib → core + Banners + VerticalSlabs + Mechanisms

## Context

`defCoreLib` is currently one plugin jar that bundles both the **engine** (custom-block
framework, heads, displays, recipes, persistence, mechanism engine) *and* a pile of **content**
(banners, vertical slabs, the whole rotation-power system, minecart item). We want to keep all
of that source in *this repo*, but compile it into **separate plugin jars** so the core stays
lean and content ships independently. This is the concrete first step of the broader
"everything depends on one shared DefCoreLib" refactor.

Target jars (all in this repo, Gradle multi-module):

| Jar | Contains | plugin.yml deps |
|-----|----------|-----------------|
| **DefCoreLib** | engine only (the shared runtime) | — (published library) |
| **Banners** | banner blocks + large/huge banner recipes | `depend: [DefCoreLib]` |
| **VerticalSlabs** | slab blocks (YAML-only) | `depend: [DefCoreLib]` |
| **Mechanisms** | rotation-power system + minecart item/recipe | `depend: [DefCoreLib]`, `softdepend: [Banners]` |

**Verified feasibility:** `CustomBlockRegistry.register()` auto-registers a type's recipes when
the registry is already `finalized` ([CustomBlockRegistry.java:184](../../src/main/java/anon/def9a2a4/corelib/CustomBlockRegistry.java#L184)).
Feature plugins load after core (via `depend`), so their `register(...)` / `BlockLoader.load(...)`
calls in their own `onEnable` register blocks **and** recipes immediately — the exact path
`Pipes` already uses ([PipesPlugin.java:79-80](../../../Pipes/src/main/java/anon/def9a2a4/pipes/PipesPlugin.java#L79)).
No new core API is required for the basic split.

---

## Gradle multi-module layout

```
settings.gradle.kts        include("core", "banners", "vertical-slabs", "mechanisms")
buildSrc/ (or convention plugin)   shared: java 21, paper-api compileOnly, bstats relocate,
                                   processResources { expand(version) }
core/            build.gradle.kts  shadowJar + maven-publish (the ONLY published artifact)
banners/         build.gradle.kts  compileOnly(project(":core"))
vertical-slabs/  build.gradle.kts  compileOnly(project(":core"))
mechanisms/      build.gradle.kts  compileOnly(project(":core"))
                                   compileOnly(project(":banners"))   // symbols only; NOT shaded
```

- Only **core** keeps `maven-publish` (it's the library others compile against).
- Feature modules depend on `:core` with **`compileOnly`** (the runtime copy comes from the
  installed DefCoreLib plugin — never shade it, per the classloader findings).
- Each feature module ships its own `plugin.yml` (own name, main class, `depend`/`softdepend`)
  and its own resource YAML.
- **Package renaming (do it):** move each feature's classes into its own package
  (`anon.def9a2a4.banners`, `.verticalslabs`, `.mechanisms`) instead of leaving them in
  `anon.def9a2a4.corelib`. Split-packages across separate plugin classloaders are a known
  Paper hazard (global by-name class lookup); distinct packages avoid it.

---

## What moves where

### Core (stays) — the engine
Custom-block framework (`CustomHeadBlock`, `CustomBlockRegistry`, `BlockLoader`), `HeadUtil`,
`DisplayUtil`/animation, recipe infra, all the world event handlers, hint/PDC persistence,
`GlueManager`, and the **mechanism engine**: `Mechanism`, `BasicMechanism`, `MechanismRegistry`,
and `MechanismMinecartManager` (the minecart *engine* — assembly/tick/transform stays; only its
item+recipe leave).

### Banners → `Banners` plugin
- Classes: `BannerManager` (582), `BannerTier` (11), `LargeBannerRecipes` (207). ~800 LOC.
- Resource: `banner-config.yml`.
- Coupling is **banners → core** (DisplayUtil, events) — clean, one-directional.
- Init moves from `CoreLibPlugin` to `BannersPlugin.onEnable()`.

### VerticalSlabs → `VerticalSlabs` plugin
- Resource only: `slabs.yml` (2,814 lines). **No Java.**
- Loads via `BlockLoader.load(slabs.yml, registry, logger)` in `VerticalSlabsPlugin.onEnable()`.
- **Prerequisite:** BlockDisplay support (`BlockDisplayEntityConfig`) must exist in core — it's
  an *additive* core feature already specced in `docs/todo/vertical-slabs.md`. Land that in core
  first (it doesn't touch existing ItemDisplay code), then slabs is a pure data move.

### Mechanisms → `Mechanisms` plugin
- Classes: `RotationNetwork` (689), `RotationBlocks` (1061), `RotationRotator` (267),
  `RotationConfig` (167), `EngineFuelManager`, `MachineRecipes`. Rotation depends on the core
  engine (`RotationRotator` → `MechanismRegistry`) — one-directional, so it moves cleanly.
- Resources: `rotation-blocks.yml`, `rotation-config.yml`, `mill-recipes.yml`,
  `press-recipes.yml`, plus the **minecart content**: the `mechanism_minecart` item entry (lift
  it out of `demo-blocks.yml`) and `mechanism-minecart-blocks.yml`, plus a new minecart crafting
  recipe (none exists today). The `rotation:wrench` item (from `corelib-items.yml`) and the
  mill/press output items (`custom-items.yml` juices/oils) move here too.
- Init moves from `CoreLibPlugin` to `MechanismsPlugin.onEnable()`: build `RotationConfig`,
  `RotationNetwork`, `EngineFuelManager`, load the rotation YAMLs, call
  `RotationBlocks.register(...)`, register `RotationRotator`, register the minecart item/recipe.

---

## Two decouplings the core needs (small, mechanical)

1. **`SpinDirection` moves into core.** Today it's `RotationNetwork.SpinDirection`, but core's
   `CustomBlockRegistry.setAnimationDirection(...)` references it for display animation
   ([CustomBlockRegistry.java:162 & ~873](../../src/main/java/anon/def9a2a4/corelib/CustomBlockRegistry.java#L162)).
   If `RotationNetwork` leaves, core can't import it. Move the CW/CCW enum to
   `anon.def9a2a4.corelib.SpinDirection` (neutral animation concept); Mechanisms imports it from
   core. ~3-line change.

2. **`bannerTier` leaves core's `CustomHeadBlock`.** The `@Nullable BannerTier bannerTier` field
   is windmill-specific content leaking into the generic core type. Remove it from
   `CustomHeadBlock` (field, ctor, accessor, builder). Mechanisms tracks windmill→tier in its own
   map instead. Removes the last banner reference from core.

Also strip from `CoreLibPlugin`: banner init (`BannerManager`/`LargeBannerRecipes`), rotation
init (network/fuel/`RotationBlocks.register`/`RotationRotator`), the slab/rotation YAML loads,
the minecart item load, and the windmill-banner craft hook (`captureBannerIngredients`,
`windmillForTier`) — that hook moves to Mechanisms (see below). `MechanismMinecartManager` init
**stays**.

---

## The interesting part: windmill ↔ banner soft-dependency

Windmills live in **Mechanisms**; large/huge windmill crafting consumes large/huge banners from
**Banners**. Required behavior: *if Banners is absent, Mechanisms still loads fine and plain
windmills still work; only large/huge windmills become uncraftable.*

Today this lives in core as `captureBannerIngredients()` (intercepts the windmill craft, reads
banner tier via `LargeBannerRecipes.isLargeBanner/isHugeBanner/stripTier`, swaps the result to
`large_windmill`/`huge_windmill`). It moves to Mechanisms as an **isolated, guarded bridge**:

- `Mechanisms` declares `softdepend: [Banners]` and `compileOnly(project(":banners"))` (compiles
  against `LargeBannerRecipes`/`BannerTier` symbols; does **not** shade them).
- All Banners-touching code lives in one class, e.g. `WindmillBannerBridge`, that is only
  **instantiated / registered** when `Bukkit.getPluginManager().isPluginEnabled("Banners")`.
  Because the class is never loaded when Banners is absent, the JVM never tries to link the
  missing `LargeBannerRecipes` (no `NoClassDefFoundError`). This is the standard soft-depend
  isolation pattern.
- Natural degradation falls out for free: without Banners there are no large/huge banner items
  to craft with, so even the plain-banner windmill recipe (`tag: banners`, vanilla) keeps
  working; only the tier upgrade path disappears. Exactly the intended UX.

(If we later want zero compile coupling, Banners can expose a tiny service via Bukkit
`ServicesManager` that Mechanisms looks up — the Vault pattern. Not needed for the first cut.)

---

## Open decisions (need a call)

- **Demo / dev-tooling placement:** `DoorDemo`, `ShowcaseBuilder`/`ShowcaseSpec`/`ShowcaseRunner`
  (+ `showcases.yml`), and `GlueAuthoring` (slime-glue authoring UX). `GlueManager` clearly stays
  in core (engine). Recommendation: **DoorDemo + GlueAuthoring stay in core** (engine
  demos/authoring reused by minecart), **ShowcaseBuilder + showcases.yml move to Mechanisms**
  (they showcase rotation content). Confirm.
- **demo-blocks.yml / custom-items.yml split:** these mix core demos with rotation/minecart
  content. Plan: extract the minecart item + rotation-adjacent items into Mechanisms YAML; leave
  generic demos in core (or drop them from the shipped core jar entirely). Confirm whether core
  should still ship *any* demo blocks.

---

## Execution order

1. **Multi-module scaffold** — convert to Gradle modules (`core` + 3 features), shared convention
   config, per-module `plugin.yml`. Core still builds/behaves identically (features empty).
2. **Core decouplings** — move `SpinDirection` into core; remove `bannerTier` from
   `CustomHeadBlock`; land BlockDisplay support (for slabs) if not already in.
3. **VerticalSlabs** (easiest, pure data) — move `slabs.yml`, wire `VerticalSlabsPlugin.onEnable`.
   Remove slab load from core. Verify slabs place/craft with core installed.
4. **Banners** — move 3 classes + `banner-config.yml`; wire `BannersPlugin.onEnable`; remove
   banner init from core.
5. **Mechanisms** — move rotation classes + YAML + minecart item/recipe; wire
   `MechanismsPlugin.onEnable`; move the windmill-banner hook into the guarded `WindmillBannerBridge`;
   remove rotation init from core.
6. **Regression** — DoorDemo + minecart (core) still assemble/rotate; rotators work with
   Mechanisms installed; windmills tier up with Banners installed and degrade gracefully without.

---

## Verification

- Build all four jars. Drop DefCoreLib + one feature at a time onto a test server.
- **Slabs:** place each variant, craft via recipe, BlockDisplay renders per face.
- **Banners:** place flag/bed banners, craft large→huge banners.
- **Mechanisms:** rotation network powers (water wheel → shaft → millstone); rotator swings a
  glued structure; minecart assembles on activator rail; plain windmill crafts.
- **Soft-dep matrix:** Mechanisms **without** Banners → loads clean, plain windmill craftable,
  large/huge windmill uncraftable, no errors in log. Mechanisms **with** Banners → large/huge
  banners swap the windmill result to the right tier.
- **Shared-runtime:** install DefCoreLib + Banners + Mechanisms + Pipes together → one
  `CustomBlockRegistry`, no duplicate-recipe-key errors, all blocks coexist.
