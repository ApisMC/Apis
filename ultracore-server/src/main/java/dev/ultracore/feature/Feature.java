package dev.ultracore.feature;

/**
 * Compile-time indexed kill-switch flags for O(1) bitmask checks in hot tick paths.
 */
public enum Feature {
    // 1. Worlds & Dimensions
    WORLDS_DISABLE_CUSTOM_WORLDS("worlds.disable-custom-worlds"),
    WORLDS_DISABLE_PORTALS("worlds.disable-portals"),

    // 2. Entities & AI
    AI_DISABLE_GLOBALLY("entities.ai.disable-globally"),
    AI_DISABLE_GOAL_SELECTOR("entities.ai.disable-goal-selector"),
    AI_DISABLE_BRAIN("entities.ai.disable-brain"),
    AI_DISABLE_PATHFINDING("entities.ai.disable-pathfinding"),
    AI_DISABLE_SENSORS("entities.ai.disable-sensors"),
    ENTITIES_DISABLE_COLLISIONS("entities.ai.disable-collisions"),

    // 3. Chunks & Generation
    CHUNKS_DISABLE_STRUCTURES("chunks.disable-structures"),
    CHUNKS_DISABLE_BIOME_DECORATION("chunks.disable-biome-decoration"),
    CHUNKS_DISABLE_TICKING("chunks.disable-chunk-ticking"),
    CHUNKS_DISABLE_RANDOM_TICKS("chunks.disable-random-ticks"),
    CHUNKS_DISABLE_BLOCK_TICKS("chunks.disable-block-ticks"),
    CHUNKS_DISABLE_FLUID_TICKS("chunks.disable-fluid-ticks"),

    // 4. Gameplay & Environment
    GAMEPLAY_DISABLE_WEATHER("gameplay.disable-weather"),
    GAMEPLAY_DISABLE_RAIDS("gameplay.disable-raids"),
    GAMEPLAY_DISABLE_REDSTONE("gameplay.disable-redstone"),
    GAMEPLAY_DISABLE_NATURAL_SPAWNING("gameplay.disable-natural-spawning"),
    GAMEPLAY_DISABLE_CUSTOM_SPAWNERS("gameplay.disable-custom-spawners"),
    GAMEPLAY_DISABLE_MONSTERS("gameplay.disable-monsters"),
    GAMEPLAY_DISABLE_ANIMALS("gameplay.disable-animals"),

    // 5. Network & Analytics
    ANALYTICS_DISABLE_METRICS_AND_PROFILER("network.analytics.disable-metrics-and-profiler"),
    ANALYTICS_DISABLE_DEBUG_SYNCHRONIZERS("network.analytics.disable-debug-synchronizers"),

    // 6. Ephemeral Container Lifecycle & Storage
    STORAGE_EPHEMERAL_MEMORY_ONLY_WORLD("storage.ephemeral.memory-only-world"),
    STORAGE_EPHEMERAL_FAST_SHUTDOWN("storage.ephemeral.fast-shutdown"),

    // 7. Network Tracking & Packet Filtering
    NETWORK_DISABLE_PLAYER_COLLISIONS("network.tracking.disable-player-collisions"),
    NETWORK_FAST_PACKET_FILTERING("network.tracking.fast-packet-filtering"),
    NETWORK_DISABLE_SERVER_PING_SAMPLE("network.tracking.disable-server-ping-sample"),

    // 8. Players Lifecycle & Inventory
    PLAYERS_DISABLE_DATA_SAVING("players.lifecycle.disable-player-data-saving"),
    PLAYERS_DISABLE_ADVANCEMENTS("players.lifecycle.disable-advancements"),
    PLAYERS_DISABLE_RECIPES("players.lifecycle.disable-recipes"),

    // 9. World Physics & Containers
    PHYSICS_DISABLE_BLOCK_GRAVITY("world.physics.disable-block-gravity"),
    PHYSICS_DISABLE_HOPPER_TICKING("world.physics.disable-hopper-ticking"),
    PHYSICS_DISABLE_LEAF_DECAY("world.physics.disable-leaf-decay"),
    PHYSICS_DISABLE_FIRE_SPREAD("world.physics.disable-fire-spread");

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
     * Returns the YAML configuration key corresponding to this feature flag.
     *
     * @return the configuration key
     */
    public String configKey() {
        return this.configKey;
    }
}
