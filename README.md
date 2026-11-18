# Apis (ApisMC)

High-performance Paper 26.3 fork designed for stripped, ultra-fast lobby and minigame servers with positive O(1) subsystem kill-switches and automated JVM AOT cache generation.

- **GitHub:** https://github.com/kotop21/apis-mc
- **Website & Docs:** https://kotop21.github.io/apismc/

## Key Features

- **O(1) Positive Feature Matrix (`config/apis.yml`):** Every subsystem defaults to `true` (standard Vanilla/Paper behavior) and can be toggled to `false` to skip loading, ticking, or allocating unneeded data (DFU/DataFixers, spawn chunk search/preload, registry validation, structures, biome decoration, loot tables, advancements, recipes, entity AI/collisions, eager BlockState/CraftBlockData caches, legacy bytecode rerouting, etc.).
- **Built-in Two-Phase AOT Cache Generator:** Run once with `--generate-aot` to record a server + plugin profile and compile `cache/apis.aot` for instant subsequent cold starts (`java -XX:AOTCache=cache/apis.aot -jar apis-bundler-<version>.jar --nogui`).
- **Diagnostics & Live Reload:** `/apis <status|reload>` (alias `/ap`, permission `apis.admin`).
- **Paper Compatibility:** Implements `ServerBuildInfo.BRAND_APIS_ID` (`apismc:apis`) while remaining compatible with `ServerBuildInfo.BRAND_PAPER_ID` (`papermc:paper`) for plugin compatibility.

## Building from Source

Requires Java 25:

```bash
./gradlew :apis-server:createBundlerJar -Prelease=true --no-configuration-cache
```

Checkstyle and unit tests:

```bash
./gradlew :apis-server:check --no-configuration-cache
```

Updating upstream Paper patches:

```bash
./scripts/upstreamCommit.sh
```
