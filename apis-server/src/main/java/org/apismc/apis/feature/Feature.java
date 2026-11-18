package org.apismc.apis.feature;

/**
 * Compile-time indexed capability flags for O(1) bitmask checks in hot tick paths.
 * Semantics: {@code true} keeps vanilla/Paper behavior enabled; {@code false} activates the kill-switch.
 */
public enum Feature {
    // 1. Startup & Boot
    STARTUP_DATAFIXERS("startup.datafixers"),
    STARTUP_SEARCH_SPAWN_POINT("startup.search-spawn-point"),
    STARTUP_INITIAL_CHUNK_LOAD("startup.initial-chunk-load"),
    STARTUP_MOJANG_SERVICE_DISCOVERY("startup.mojang-service-discovery"),
    STARTUP_PAPER_VERSION_CHECK("startup.paper-version-check"),
    STARTUP_VALIDATE_CREATIVE_TABS("startup.validate-creative-tabs"),
    STARTUP_VALIDATE_LOOT("startup.validate-loot"),
    STARTUP_TEST_REGISTRIES("startup.test-registries"),

    // 2. Caches (Eager vs Lazy)
    CACHES_EAGER_BLOCKSTATE("caches.eager-blockstate-cache"),
    CACHES_EAGER_CRAFTBLOCKDATA("caches.eager-craftblockdata-cache"),
    CACHES_EAGER_MAP_COLOR("caches.eager-map-color-cache"),

    // 3. Dimensions
    DIMENSIONS_CUSTOM_WORLDS("dimensions.custom-worlds"),
    DIMENSIONS_PORTALS("dimensions.portals"),

    // 4. Chunks & Tick Sub-features
    CHUNKS_STRUCTURES("chunks.structures"),
    CHUNKS_BIOME_DECORATION("chunks.biome-decoration"),
    CHUNKS_TICKING("chunks.ticking.enabled"),
    CHUNKS_RANDOM_TICKS("chunks.ticking.sub-features.random-ticks"),
    CHUNKS_BLOCK_TICKS("chunks.ticking.sub-features.block-ticks"),
    CHUNKS_FLUID_TICKS("chunks.ticking.sub-features.fluid-ticks"),

    // 5. Block Entities & Physics
    BLOCK_ENTITIES_TICKING("block-entities.ticking.enabled"),
    BLOCK_ENTITIES_HOPPERS("block-entities.ticking.sub-features.hoppers"),
    PHYSICS_REDSTONE("blocks-and-physics.redstone"),
    PHYSICS_BLOCK_GRAVITY("blocks-and-physics.block-gravity"),
    PHYSICS_LEAF_DECAY("blocks-and-physics.leaf-decay"),
    PHYSICS_FIRE_SPREAD("blocks-and-physics.fire-spread"),
    PHYSICS_ITEM_PHYSICS("blocks-and-physics.item-physics"),

    // 6. World Environment
    ENV_DAYLIGHT_CYCLE("world-environment.daylight-cycle"),
    ENV_WEATHER("world-environment.weather"),
    ENV_RAIDS("world-environment.raids"),
    ENV_SLEEP_CYCLE("world-environment.sleep-cycle"),
    ENV_END_DRAGON_FIGHT("world-environment.end-dragon-fight"),

    // 7. Spawning
    SPAWNING_NATURAL("spawning.natural.enabled"),
    SPAWNING_MONSTERS("spawning.natural.sub-features.monsters"),
    SPAWNING_ANIMALS("spawning.natural.sub-features.animals"),
    SPAWNING_CUSTOM_SPAWNERS("spawning.natural.sub-features.custom-spawners"),

    // 8. Entities & AI
    ENTITIES_AI_ENABLED("entities.ai.enabled"),
    ENTITIES_AI_GOAL_SELECTOR("entities.ai.sub-features.goal-selector"),
    ENTITIES_AI_BRAIN("entities.ai.sub-features.brain"),
    ENTITIES_AI_PATHFINDING("entities.ai.sub-features.pathfinding"),
    ENTITIES_AI_SENSORS("entities.ai.sub-features.sensors"),
    ENTITIES_COLLISIONS("entities.collisions.entities"),
    ENTITIES_PLAYER_COLLISIONS("entities.collisions.players"),

    // 9. Gameplay & Datapacks
    GAMEPLAY_ADVANCEMENTS("gameplay.advancements"),
    GAMEPLAY_RECIPES("gameplay.recipes"),
    GAMEPLAY_LOOT_TABLES("gameplay.loot-tables"),
    GAMEPLAY_COMMAND_FUNCTIONS("gameplay.command-functions"),

    // 10. Players
    PLAYERS_DATA_SAVING("players.data-saving"),
    PLAYERS_HUNGER("players.hunger"),
    PLAYERS_STATISTICS("players.statistics"),
    PLAYERS_WARDEN_TRACKER("players.warden-tracker"),

    // 11. Network, Analytics & Status
    NETWORK_GENERATE_KEYPAIR("network.generate-keypair"),
    ANALYTICS_BSTATS("network.analytics.bstats"),
    ANALYTICS_SPARK("network.analytics.spark"),
    ANALYTICS_TICK_MONITOR("network.analytics.tick-monitor"),
    ANALYTICS_DEBUG_SYNCHRONIZERS("network.analytics.debug-synchronizers"),
    NETWORK_SERVER_PING_SAMPLE("network.player-list.server-ping-sample"),
    NETWORK_TAB_LATENCY_UPDATES("network.player-list.tab-latency-updates"),
    NETWORK_FULL_PACKET_BROADCAST("network.packet-range-clamping.full-packet-broadcast"),

    // 12. Storage & System Lifecycle
    STORAGE_WORLD_SAVING("storage.world-saving"),
    SYSTEM_GRACEFUL_SHUTDOWN("system.lifecycle.graceful-shutdown"),

    // 13. Compatibility & Legacy
    LEGACY_PLUGIN_BYTECODE_REROUTING("compatibility.legacy.plugin-bytecode-rerouting"),
    LEGACY_BOOTSTRAP_DATA_TABLES("compatibility.legacy.bootstrap-data-tables"),
    LEGACY_VALIDATE_ENTITY_ATTRIBUTES("compatibility.legacy.validate-entity-attributes");

    static final int MAX_SUPPORTED_FEATURES = 128;

    final int word;
    final long bit;
    private final String configKey;

    Feature(final String configKey) {
        final int idx = this.ordinal();
        if (idx >= MAX_SUPPORTED_FEATURES) {
            throw new IllegalStateException("Feature count exceeds 128-bit bitmask capacity: " + idx);
        }
        this.configKey = configKey;
        this.word = idx >>> 6;
        this.bit = 1L << (idx & 63);
    }

    /**
     * Returns the YAML configuration key corresponding to this capability flag.
     *
     * @return the configuration key
     */
    public String configKey() {
        return this.configKey;
    }
}
