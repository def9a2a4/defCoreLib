# Plugin Split: thin recipe-provider plugins over a fat DefCoreLib

## Context

`defCoreLib` bundles the engine (custom-block framework, heads, displays, recipes, persistence,
mechanism engine) plus a lot of content: banners, vertical slabs, the rotation-power system, the
minecart, and demo blocks. We want that content to be **modular** — a server enables banners /
vertical slabs / mechanisms by installing a small companion plugin — **without** surgically
ripping the engine code apart.

**Design decision (owner):** *all the core code and the item/block definitions stay in
DefCoreLib.* The companion plugins are thin — they **provide / enable the crafting recipes** for
item groups that already live (recipe-less) in the core. "It's fine if the item technically
exists in defcorelib; the consumer plugin provides the recipe, or at any rate enables/disables
it." Demo blocks stay in core too, just **without recipes**.

So this is a **recipe-gating split, not a class-extraction split.** Items exist and function in
the core jar; they're simply **uncraftable until a companion plugin turns their recipes on**
(obtainable meanwhile via `/corelib give`). This is far lower-risk than moving stateful classes
and event handlers across plugin classloaders.

Companion jars (same repo, thin):

| Jar | Job | plugin.yml deps |
|-----|-----|-----------------|
| **DefCoreLib** | engine + ALL definitions (banners, slabs, rotation, minecart, demos), **no gated recipes registered by default** | (published library) |
| **Banners** | enable banner recipes (incl. the large/huge banner craft system) | `depend: [DefCoreLib]` |
| **VerticalSlabs** | enable `verticalslabs` recipes | `depend: [DefCoreLib]` |
| **Mechanisms** | enable `rotation` + minecart recipes; enable large/huge **windmill** tiers only if Banners present | `depend: [DefCoreLib]`, `softdepend: [Banners]` |

---

## Core change: recipe gating by namespace (the one real addition)

Today `finalizeLoading()` → `registerRecipes()` blanket-registers every type's recipes, keyed by
namespace and gated only by `type.hasRecipes()`
([CustomBlockRegistry.java:1113](../../src/main/java/anon/def9a2a4/corelib/CustomBlockRegistry.java#L1113)).
Change it to gate by an **enabled-namespace set**:

- `finalizeLoading()` registers recipes **only** for a small core allowlist (namespaces that
  should always be craftable — possibly empty). All gated namespaces (`banner*`, `verticalslabs`,
  `rotation`, minecart) are skipped by default → their items exist but are uncraftable.
- New public API on `CustomBlockRegistry`:
  - `void enableRecipes(String namespace)` — register (via existing `registerRecipesForType`)
    every already-registered type whose `namespace()` matches; idempotent.
  - `void disableRecipes(String namespace)` — `removeRecipe` those keys (mirror of the existing
    `unregisterRecipes`).
- Because `register()` already auto-registers a type's recipes when `finalized`
  ([:184-189](../../src/main/java/anon/def9a2a4/corelib/CustomBlockRegistry.java#L184)), the gate
  must also consult the enabled-set there, so late registrations respect gating.

A companion plugin's entire job becomes, in `onEnable()`:
```java
var reg = CoreLibPlugin.getInstance().getRegistry();
reg.enableRecipes("rotation");           // Mechanisms
reg.enableRecipes("minecart");           // (or whatever namespace the minecart item uses)
```
and `disableRecipes(...)` in `onDisable()`. That's the whole plugin, plus the windmill/banner
bridge below.

**Demos:** strip the `recipes:` sections from the demo YAML (or just never enable a "demo"
namespace). Demo blocks keep working; they're just not craftable. Matches "keep demos, remove
crafting recipes."

---

## What stays in core (everything) — recipes off by default

All engine code **and** all content code/definitions remain in DefCoreLib, unchanged in behavior:
- Banners: `BannerManager`, `BannerTier`, `LargeBannerRecipes`, `banner-config.yml`.
- Vertical slabs: `slabs.yml` (needs the additive BlockDisplay support from
  `docs/todo/vertical-slabs.md` — land it in core regardless).
- Rotation: `RotationNetwork`, `RotationBlocks`, `RotationRotator`, `RotationConfig`,
  `EngineFuelManager`, `MachineRecipes`, all rotation YAML.
- Minecart: `MechanismMinecartManager` + item definition + `mechanism-minecart-blocks.yml`.
- Demos, glue, showcases — all stay.

The **only** things that change in core:
1. Recipe gating (above).
2. `BannerManager`/`LargeBannerRecipes` and the windmill-tier craft hook
   (`captureBannerIngredients`) become **dormant until enabled** — see next section — rather than
   always-on in `CoreLibPlugin.onEnable()`.

No class moves, no `SpinDirection`/`bannerTier` decoupling needed (those were only required by the
abandoned class-extraction approach).

---

## The interesting part: windmill ↔ banner soft-dependency

Windmill recipes and the large/huge banner system both live in core but are **enabled by
companion plugins**, and the tiering couples the two:

- **Banners plugin** enables the banner recipe system: it activates `LargeBannerRecipes` (the
  `PrepareItemCraft`/`CraftItem` listeners for large→huge banners) and `enableRecipes("banner")`.
  Today `LargeBannerRecipes` is constructed and registered unconditionally in
  `CoreLibPlugin.onEnable()`; change core so it's constructed but **not activated** until
  `bannerManager.activate()` is called — the Banners plugin calls that in `onEnable`, and
  `deactivate()` in `onDisable`.
- **Mechanisms plugin** enables windmill recipes. The base (plain) windmill recipe uses a vanilla
  banner (`tag: banners`) and works standalone. The **large/huge windmill tier swap**
  (`captureBannerIngredients` → result becomes `large_windmill`/`huge_windmill`) only makes sense
  when large/huge banners exist, i.e. when Banners is installed.
- **Soft-dep wiring:** Mechanisms `softdepend: [Banners]`. In `onEnable`, after enabling base
  windmill recipes, it checks `isPluginEnabled("Banners")` and only then activates the windmill
  **tier** hook. Result: without Banners, Mechanisms loads clean and plain windmills craft; large/
  huge windmills are simply unobtainable (no large/huge banners exist anyway). Exactly the
  intended degradation.

Because the tier hook and banner listeners live in **core** (not in a separate classloader),
there's no cross-plugin class-linkage risk at all — the companion plugins just flip core switches.

---

## Companion plugin shape (Gradle multi-module)

```
settings.gradle.kts      include("core", "banners", "vertical-slabs", "mechanisms")
buildSrc / convention    shared: java 21, paper-api compileOnly, processResources version expand
core/                    the existing plugin + recipe-gating change + activate() switches
                         (keeps shadowJar + maven-publish)
banners/                 compileOnly(project(":core")); ~30-line plugin: activate banners
vertical-slabs/          compileOnly(project(":core")); ~15-line plugin: enableRecipes
mechanisms/              compileOnly(project(":core")); small plugin: enable rotation+minecart
                         recipes, conditional windmill-tier activation
```
- Companions `compileOnly` core (runtime copy comes from the installed DefCoreLib plugin — never
  shade it). Each ships its own `plugin.yml` (`depend`/`softdepend`) and, if it needs metrics,
  relocates its own bstats.
- Only **core** publishes to Maven.

---

## Execution order

1. **Recipe-gating in core** — add enabled-namespace set + `enableRecipes`/`disableRecipes`;
   default core to register no gated-namespace recipes. Add `activate()`/`deactivate()` to the
   banner subsystem and make the windmill-tier hook activatable. Core alone now ships all items
   uncraftable (verify via `/corelib give` they still function).
2. **Gradle multi-module scaffold** — split into `core` + 3 companion modules; core builds
   identically.
3. **VerticalSlabs** companion — `enableRecipes("verticalslabs")`. Verify slabs craft only with it
   installed. (Prereq: BlockDisplay support in core.)
4. **Banners** companion — activate banner system + `enableRecipes("banner")`. Verify banners
   place/craft only with it installed.
5. **Mechanisms** companion — enable rotation + minecart recipes; conditional windmill-tier
   activation on Banners. Add a minecart crafting recipe (none exists today).
6. **Regression** — engine (doors, minecart assembly, rotators) still works from core; recipes
   appear/disappear with companions; windmills tier up with Banners, degrade without.

---

## Verification

- Build all four jars. **DefCoreLib alone:** every item exists (via `/corelib give`), functions
  in-world, but nothing gated is craftable; no errors.
- **+ VerticalSlabs:** slab recipes appear; remove plugin → recipes gone, placed slabs still work.
- **+ Banners:** banner placement + large/huge banner crafting work only when installed.
- **+ Mechanisms, no Banners:** loads clean; rotation network + minecart craftable; plain windmill
  craftable; large/huge windmill uncraftable; no log errors.
- **+ Mechanisms + Banners:** large/huge banners swap the windmill result to the right tier.
- **Shared runtime:** DefCoreLib + all three companions + Pipes together → one
  `CustomBlockRegistry`, no duplicate-recipe-key errors, everything coexists.

---

## Note / tension to confirm

This model leaves the content **code** physically in the DefCoreLib jar (per "all core code stays
in defcorelib") — so the core jar is *not* slimmed; the split buys **modularity of craftability**,
not jar size. That reverses the earlier "compile the content into separate jars" framing in favor
of the simpler recipe-gating approach you described. If you actually want some content *code*
(e.g. the whole rotation package) physically out of the core jar as well, that's the heavier
class-extraction path (documented in this file's git history) and can be layered on later.
