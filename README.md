# Thaumic All Aspect

[![Build](https://github.com/Sunsmilktea/ThaumicAllAspect/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/Sunsmilktea/ThaumicAllAspect/actions/workflows/build-and-test.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](./LICENSE)

A [Thaumcraft 4](https://www.curseforge.com/minecraft/mc-mods/thaumcraft) addon for **Minecraft 1.7.10** that automatically assigns missing aspects to every item, block, fluid, and entity.

Existing aspects — whether registered by Thaumcraft itself or by any other mod (including ModTweaker/CraftTweaker and GTNH addons) — are **never overwritten**.

---

## Overview

"Thaumic All Aspect" (TAA) fills the aspect gap in large modpacks. In an unmodified Thaumcraft installation, the vast majority of modded items have no aspects at all, which makes them useless for infusion, alchemy, and research. TAA scans the entire game once, after all mods have loaded, and derives sensible aspects for everything that is still missing one.

## Features

- **Complete coverage** — items, blocks, fluids, *and* entities.
- **Never overwrites** — anything already registered in Thaumcraft (vanilla aspects, other mods, wildcard-meta and item-damage-range registrations) is skipped.
- **Recipe-based derivation** — aspects are summed from crafting, furnace, and Thaumcraft recipes, with a configurable decay per derivation layer.
- **OreDictionary inheritance** — items inherit aspects from their OreDictionary donors when no recipe exists.
- **Type / keyword fallbacks** — blocks and items without recipes get aspects from their material type and multilingual name keywords.
- **Entity derivation** — mobs get aspects from inheritance, mob type (animal / water / undead / boss), and drops.
- **Fully configurable** — every fallback can be overridden from `config/ThaumicAllAspect/` without touching code.
- **Cache-based** — scan results are persisted so later loads (and servers) reuse them.
- **Designed for GTNH & large modpacks** — hardened reflection, bounded recursion, and graceful skip of broken recipes.

## Requirements

| Dependency  | Version                    |
|-------------|----------------------------|
| Minecraft   | 1.7.10                     |
| Forge       | 10.13.4.1614 or newer      |
| Thaumcraft  | 4.2.3.5 or newer           |
| Baubles     | 1.0.1.10 or newer          |

## How it works

TAA hooks `FMLLoadCompleteEvent`, which fires **after** every mod has finished registering items, blocks, recipes, and OreDictionary entries. The scan then:

1. Loads the previous scan cache (`config/ThaumicAllAspect/aspect-cache.cfg`) as a seed.
2. Loads the fallback configs (keyword / item / fluid / entity).
3. Builds indices for OreDictionary, crafting, furnace, and Thaumcraft recipes (plus NEI).
4. Runs a recipe-first pipeline over multiple rounds to propagate aspects through crafting chains.
5. Scans every item and block that is still missing aspects, deriving from: **recipe → OreDictionary → type → keyword fallback**.
6. Scans fluids, then mod-specific recipes (via reflection, e.g. Botania).
7. Recovers failed IDs, sweeps metadata variants, and adjusts small-piece ratios (e.g. nuggets).
8. Writes the result cache and scan log.
9. Finally scans entities, deriving aspects for any mob that is still missing them.

Every step is bounded (max recipe depth, max pipeline rounds) and isolated, so a single broken recipe or unexpected mod can never crash the game at load time.

## Configuration

All configuration lives under **`config/ThaumicAllAspect/`**:

| File                      | Purpose                                                                 |
|---------------------------|-------------------------------------------------------------------------|
| `thaumicallaspect.cfg`  | Main settings (log level, decay, depth, rounds).                        |
| `keyword-fallback.cfg`  | `keyword=aspect=amount,…` — add aspects when an item's name contains the keyword. |
| `item-fallback.cfg`     | `modid:name@meta=aspect=amount,…` — assign exact aspects to an item/block; `fluid:name=…` for fluids. |
| `entity-fallback.cfg`   | `EntityID=aspect=amount,…` — override entity aspects (modpack customization). |
| `aspect-cache.cfg`      | Generated cache, written after every scan and reused on the next load.  |

See **[wiki/Configuration.md](wiki/Configuration.md)** for the full reference and examples.

The fallback config files are created automatically with comment-only templates on first launch — TAA ships **no** active preset lines, so vanilla and mod defaults are preserved until you choose to override them.

## Log files

Scan diagnostics are written to the `logs/` directory:

| File                                | Content                                        |
|-------------------------------------|------------------------------------------------|
| `ThaumicAllAspect-scan.log`       | Per-item scan log (full level only).           |
| `ThaumicAllAspect-cache.cfg`      | Human-readable dump of the derived cache.      |
| `ThaumicAllAspect-failures.txt`   | Items/entities that could not be assigned.     |

The amount of logging is controlled by `performance.scanLogLevel` (0 = full, 1 = summary, 2 = off).

## Building

```bash
./gradlew build
```

Artifacts are written to `build/libs/`.

## License

[MIT](LICENSE)
