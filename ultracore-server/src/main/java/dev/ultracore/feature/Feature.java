package dev.ultracore.feature;

/**
 * Compile-time indexed capability flags for O(1) bitmask checks in hot tick paths.
 * Semantics: {@code true} keeps vanilla/Paper behavior enabled; {@code false} activates the kill-switch.
 */
public enum Feature {
    // 1. Startup & Bootstrap
    STARTUP_DATAFIXERS("startup.datafixers"),
    STARTUP_SEARCH_SPAWN_POINT("startup.search-spawn-point"),
    STARTUP_INITIAL_CHUNK_LOAD("startup.initial-chunk-load"),
    STARTUP_VALIDATE_REGISTRIES("startup.validate-registries"),
    STARTUP_MOJANG_SERVICE_DISCOVERY("startup.mojang-service-discovery"),
    STARTUP_PAPER_VERSION_CHECK("startup.paper-version-check"),

    // 2. Worlds & Dimensions
    WORLDS_CUSTOM_WORLDS("worlds.custom-worlds"),
    WORLDS_PORTALS("worlds.portals"),

    // 3. Entities & AI
    AI_ENABLED("entities.ai.enabled"),
    AI_GOAL_SELECTOR("entities.ai.goal-selector"),
    AI_BRAIN("entities.ai.brain"),
    AI_PATHFINDING("entities.ai.pathfinding"),
    AI_SENSORS("entities.ai.sensors"),
    ENTITIES_COLLISIONS("entities.ai.collisions"),

    // 4. Chunks & Generation
    CHUNKS_STRUCTURES("chunks.structures"),
    CHUNKS_BIOME_DECORATION("chunks.biome-decoration"),
    CHUNKS_TICKING("chunks.chunk-ticking"),
    CHUNKS_RANDOM_TICKS("chunks.random-ticks"),
    CHUNKS_BLOCK_TICKS("chunks.block-ticks"),
    CHUNKS_FLUID_TICKS("chunks.fluid-ticks"),

    // 5. Gameplay & Environment
    GAMEPLAY_WEATHER("gameplay.weather"),
    GAMEPLAY_RAIDS("gameplay.raids"),
    GAMEPLAY_REDSTONE("gameplay.redstone"),
    GAMEPLAY_NATURAL_SPAWNING("gameplay.natural-spawning"),
    GAMEPLAY_CUSTOM_SPAWNERS("gameplay.custom-spawners"),
    GAMEPLAY_MONSTERS("gameplay.monsters"),
    GAMEPLAY_ANIMALS("gameplay.animals"),
    GAMEPLAY_ADVANCEMENTS("gameplay.advancements"),
    GAMEPLAY_RECIPES("gameplay.recipes"),
    GAMEPLAY_WARDEN_AND_SLEEP("gameplay.warden-and-sleep"),
    GAMEPLAY_COMMAND_FUNCTIONS("gameplay.command-functions"),

    // 6. Network & Analytics
    NETWORK_GENERATE_KEYPAIR("network.generate-keypair"),
    ANALYTICS_METRICS_AND_PROFILER("network.analytics.metrics-and-profiler"),
    ANALYTICS_DEBUG_SYNCHRONIZERS("network.analytics.debug-synchronizers"),
    NETWORK_PLAYER_COLLISIONS("network.tracking.player-collisions"),
    NETWORK_FULL_PACKET_BROADCAST("network.tracking.full-packet-broadcast"),
    NETWORK_SERVER_PING_SAMPLE("network.tracking.server-ping-sample"),
    NETWORK_TAB_LATENCY_UPDATES("network.tracking.tab-latency-updates"),

    // 7. Ephemeral Container Lifecycle & Storage
    STORAGE_WORLD_SAVING("storage.ephemeral.world-saving"),
    STORAGE_GRACEFUL_SHUTDOWN("storage.ephemeral.graceful-shutdown"),

    // 8. Players Lifecycle
    PLAYERS_DATA_SAVING("players.lifecycle.player-data-saving"),
    PLAYERS_HUNGER("players.lifecycle.hunger"),
    PLAYERS_STATISTICS("players.lifecycle.statistics"),

    // 9. World Time & Physics
    WORLD_DAYLIGHT_CYCLE("world.daylight-cycle"),
    PHYSICS_BLOCK_GRAVITY("world.physics.block-gravity"),
    PHYSICS_HOPPER_TICKING("world.physics.hopper-ticking"),
    PHYSICS_BLOCK_ENTITY_TICKING("world.physics.block-entity-ticking"),
    PHYSICS_ITEM_PHYSICS("world.physics.item-physics"),
    PHYSICS_LEAF_DECAY("world.physics.leaf-decay"),
    PHYSICS_FIRE_SPREAD("world.physics.fire-spread");

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
