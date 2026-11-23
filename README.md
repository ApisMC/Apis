<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="public/badge-dark.svg" />
    <source media="(prefers-color-scheme: light)" srcset="public/badge-white.svg" />
    <img alt="Apis" src="public/badge-white.svg" width="250" />
  </picture>

  <p>
    <a href="https://apismc.github.io/"><img src="https://img.shields.io/badge/Docs-apismc.github.io-2D333B?style=flat&logo=gitbook&logoColor=FEC106&labelColor=1E222A" alt="Documentation" /></a>
    <a href="https://github.com/ApisMC/Apis/releases"><img src="https://img.shields.io/github/v/release/ApisMC/Apis?style=flat&logo=github&logoColor=FEC106&label=Release&labelColor=1E222A&color=2D333B" alt="Release" /></a>
    <a href="https://github.com/ApisMC/Apis/actions/workflows/builds.yml"><img src="https://img.shields.io/github/actions/workflow/status/ApisMC/Apis/builds.yml?style=flat&logo=githubactions&logoColor=FEC106&label=Builds&labelColor=1E222A" alt="Builds" /></a>
    <img src="https://img.shields.io/badge/Runtime-Java-2D333B?style=flat&logo=openjdk&logoColor=FEC106&labelColor=1E222A" alt="Java" />
    <a href="https://docs.papermc.io/paper/"><img src="https://img.shields.io/badge/API-Paper-2D333B?style=flat&logo=spigotmc&logoColor=FEC106&labelColor=1E222A" alt="Paper API" /></a>
  </p>

  <p>
    High-performance Paper fork designed for stripped, ultra-fast lobby and minigame servers with positive O(1) subsystem kill-switches and JVM AOT cache support.
  </p>
</div>

## Key Features

* **O(1) Positive Feature Matrix (`configs/apis.yml`):** Every subsystem defaults to `true` (standard Vanilla/Paper behavior) and can be toggled to `false` to skip loading, ticking, or allocating unneeded data (DFU/DataFixers, spawn chunk search/preload, registry validation, structures, biome decoration, loot tables, advancements, recipes, entity AI/collisions, eager BlockState/CraftBlockData caches, legacy bytecode rerouting, etc.).
* **Diagnostics & Live Reload:** `/apis <status|reload>` (alias `/ap`, permission `apis.admin`), plus standalone worlds management via `/world`.
* **Paper Compatibility:** Implements `ServerBuildInfo.BRAND_APIS_ID` (`apismc:apis`) while remaining compatible with `ServerBuildInfo.BRAND_PAPER_ID` (`papermc:paper`) for plugin compatibility.

## Building & Distribution

Requires Java 25:

* **Public distribution (Paperclip installer JAR — recommended):**
  ```bash
  ./gradlew :apis-server:createPaperclipJar -Prelease=true
  ```
  Outputs `apis-server/build/libs/apis-paperclip-<version>.jar`. Paperclip downloads the official vanilla server JAR on the user's machine and applies binary patches locally.
* **Local development & testing only (Bundler JAR):**
  ```bash
  ./gradlew :apis-server:createBundlerJar -Prelease=true
  ```
  > **Warning:** `createBundlerJar` packages the compiled patched server JAR directly and is intended **strictly for local development and testing**, not public redistribution.
* **Fast Apis tests & Checkstyle:**
  ```bash
  ./gradlew quickCheck
  ```
* **Full upstream + Apis verification:**
  ```bash
  ./gradlew check
  ```

Updating upstream Paper patches:

```bash
./scripts/upstreamCommit.sh
```

## License & Acknowledgements

Apis is licensed under the [GNU General Public License version 3](LICENSE.md) (see [LICENSE.md](LICENSE.md) and [`licenses/`](licenses/)).

Built on [Paper](https://papermc.io/), [Spigot](https://www.spigotmc.org/), and [Bukkit](https://bukkit.org/).

> **Disclaimer:** NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
