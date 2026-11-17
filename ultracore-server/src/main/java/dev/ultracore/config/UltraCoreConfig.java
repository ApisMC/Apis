package dev.ultracore.config;

import dev.ultracore.feature.FeatureRegistry;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Early-loaded YAML configuration for UltraCore kill-switches (all comments in ENG | UA).
 */
@ConfigSerializable
public final class UltraCoreConfig {

    public static final String DEFAULT_FILE_NAME = "ultracore.yml";
    private static final Logger LOGGER = LoggerFactory.getLogger("UltraCore");

    private static final String HEADER = """
        UltraCore Feature Kill-Switch Configuration | Конфігурація системних вимикачів UltraCore
        Designed for ephemeral Docker/RustyConnector instances | Оптимізовано для Docker-контейнерів та RustyConnector
        """;

    private static volatile UltraCoreConfig instance = new UltraCoreConfig();
    private static volatile Path configPath = Path.of(UltraCorePaths.CONFIG_DIR, DEFAULT_FILE_NAME);

    @Setting("worlds")
    @Comment("Worlds & dimensions loading/ticking switches | Вимикачі завантаження та тіків світів і вимірів")
    public WorldsSection worlds = new WorldsSection();

    @Setting("entities")
    @Comment("Entity & AI subsystem switches | Вимикачі сутностей та штучного інтелекту")
    public EntitiesSection entities = new EntitiesSection();

    @Setting("chunks")
    @Comment("Chunk generation & ticking switches | Вимикачі генерації та тіків чанків")
    public ChunksSection chunks = new ChunksSection();

    @Setting("gameplay")
    @Comment("Vanilla gameplay & world environment switches | Вимикачі ігрових механік та оточення")
    public GameplaySection gameplay = new GameplaySection();

    @Setting("network")
    @Comment("Network tracking, packet filtering & analytics switches | Вимикачі мережі, трекінгу та аналітики")
    public NetworkSection network = new NetworkSection();

    @Setting("storage")
    @Comment("Container storage & lifecycle switches | Вимикачі життєвого циклу контейнера та сховища")
    public StorageSection storage = new StorageSection();

    @Setting("players")
    @Comment("Player lifecycle, saves, advancements & recipes switches | Вимикачі збереження гравців, досягнень та рецептів")
    public PlayersSection players = new PlayersSection();

    @Setting("world")
    @Comment("Block physics & container ticking switches | Вимикачі фізики блоків та контейнерів")
    public WorldSection world = new WorldSection();

    public static UltraCoreConfig get() {
        return instance;
    }

    public static Path configPath() {
        return configPath;
    }

    /**
     * Loads ultracore.yml as early as possible (before Minecraft Bootstrap / world allocation).
     *
     * @param path path to the YAML configuration file
     * @return loaded and sanitized configuration instance
     */
    public static synchronized UltraCoreConfig loadEarly(final Path path) {
        configPath = path;
        final UltraCoreConfig loaded = loadOrCreate(path, LOGGER);
        loaded.sanitize(LOGGER);
        instance = loaded;
        FeatureRegistry.applyEarly(loaded);
        LOGGER.info(
            "[UltraCore] Loaded {} (active kill-switches: {})",
            path.toAbsolutePath(),
            FeatureRegistry.activeKillSwitches().size()
        );
        return loaded;
    }

    /**
     * Reloads configuration from disk and refreshes O(1) bitmasks + registry sets.
     *
     * @return reloaded configuration instance
     */
    public static synchronized UltraCoreConfig reload() {
        return loadEarly(configPath);
    }

    static UltraCoreConfig loadOrCreate(final Path path, final Logger logger) {
        try {
            final Path parent = path.toAbsolutePath().getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (final IOException ex) {
            logger.error("[UltraCore] Cannot create parent directory for {}. Using safe defaults.", path, ex);
            return new UltraCoreConfig();
        }

        final YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(path)
            .nodeStyle(NodeStyle.BLOCK)
            .indent(2)
            .defaultOptions(opts -> opts.shouldCopyDefaults(true).header(HEADER))
            .build();

        try {
            final boolean existed = Files.exists(path);
            final CommentedConfigurationNode root = existed ? loader.load() : loader.createNode();
            final @Nullable UltraCoreConfig cfg = root.get(UltraCoreConfig.class);
            final UltraCoreConfig effective = cfg != null ? cfg : new UltraCoreConfig();
            root.set(UltraCoreConfig.class, effective);
            saveWithComments(path, loader, root);
            return effective;
        } catch (final IOException ex) {
            logger.error("[UltraCore] Failed to parse {}. Backing up broken config and using defaults.", path, ex);
            backupBrokenConfig(path, logger);
            final UltraCoreConfig fallback = new UltraCoreConfig();
            try {
                final CommentedConfigurationNode root = loader.createNode();
                root.set(UltraCoreConfig.class, fallback);
                saveWithComments(path, loader, root);
            } catch (final IOException saveEx) {
                logger.warn("[UltraCore] Could not write default config to {}", path, saveEx);
            }
            return fallback;
        }
    }

    private static void saveWithComments(
        final Path path,
        final YamlConfigurationLoader baseLoader,
        final CommentedConfigurationNode root
    ) throws IOException {
        final StringWriter writer = new StringWriter();
        final YamlConfigurationLoader bufferLoader = YamlConfigurationLoader.builder()
            .nodeStyle(NodeStyle.BLOCK)
            .indent(2)
            .defaultOptions(baseLoader.defaultOptions())
            .sink(() -> new BufferedWriter(writer))
            .build();
        bufferLoader.save(root);
        Files.writeString(path, renderNodeComments(writer.toString(), root), StandardCharsets.UTF_8);
    }

    private static String renderNodeComments(final String rawYaml, final CommentedConfigurationNode root) {
        final StringBuilder out = new StringBuilder(rawYaml.length() + 2048);
        final Deque<String> keyPath = new ArrayDeque<>();
        for (final String line : rawYaml.split("\\r?\\n")) {
            final int indentSpaces = countLeadingSpaces(line);
            final String trimmed = line.substring(indentSpaces);
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                out.append(line).append('\n');
                continue;
            }
            final int colonIdx = findKeySeparator(trimmed);
            if (colonIdx <= 0) {
                out.append(line).append('\n');
                continue;
            }
            final int depth = indentSpaces / 2;
            while (keyPath.size() > depth) {
                keyPath.removeLast();
            }
            final String key = unquoteKey(trimmed.substring(0, colonIdx));
            keyPath.addLast(key);

            final CommentedConfigurationNode node = root.node(keyPath.toArray());
            final @Nullable String comment = node.comment();
            if (comment != null && !comment.isBlank()) {
                final String indentPrefix = " ".repeat(indentSpaces);
                for (final String commentLine : comment.split("\n")) {
                    out.append(indentPrefix).append("# ").append(commentLine).append('\n');
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static int countLeadingSpaces(final String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static int findKeySeparator(final String trimmed) {
        final int idx = trimmed.indexOf(':');
        if (idx <= 0) {
            return -1;
        }
        return (idx == trimmed.length() - 1 || trimmed.charAt(idx + 1) == ' ') ? idx : -1;
    }

    private static String unquoteKey(final String rawKey) {
        if (rawKey.length() >= 2 && ((rawKey.startsWith("'") && rawKey.endsWith("'"))
            || (rawKey.startsWith("\"") && rawKey.endsWith("\"")))) {
            return rawKey.substring(1, rawKey.length() - 1);
        }
        return rawKey;
    }

    private static void backupBrokenConfig(final Path path, final Logger logger) {
        if (Files.notExists(path)) {
            return;
        }
        final Path backup = path.resolveSibling(path.getFileName() + ".broken");
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            logger.warn("[UltraCore] Could not back up malformed config {}", path, ex);
        }
    }

    void sanitize(final Logger logger) {
        if (this.worlds == null) this.worlds = new WorldsSection();
        if (this.worlds.disabledDimensions == null) this.worlds.disabledDimensions = new ArrayList<>();
        if (this.worlds.disabledTickingDimensions == null) this.worlds.disabledTickingDimensions = new ArrayList<>();
        if (this.entities == null) this.entities = new EntitiesSection();
        if (this.entities.ai == null) this.entities.ai = new AiSection();
        if (this.entities.ai.disabledTypes == null) this.entities.ai.disabledTypes = new ArrayList<>();
        if (this.chunks == null) this.chunks = new ChunksSection();
        if (this.gameplay == null) this.gameplay = new GameplaySection();
        if (this.network == null) this.network = new NetworkSection();
        if (this.network.analytics == null) this.network.analytics = new AnalyticsSection();
        if (this.network.tracking == null) this.network.tracking = new TrackingSection();
        if (this.storage == null) this.storage = new StorageSection();
        if (this.storage.ephemeral == null) this.storage.ephemeral = new EphemeralStorageSection();
        if (this.players == null) this.players = new PlayersSection();
        if (this.players.lifecycle == null) this.players.lifecycle = new PlayerLifecycleSection();
        if (this.world == null) this.world = new WorldSection();
        if (this.world.physics == null) this.world.physics = new PhysicsSection();

        this.network.tracking.projectileTrackingRange = clampRange(
            this.network.tracking.projectileTrackingRange, 0, 256, "network.tracking.projectile-tracking-range", logger
        );
        this.network.tracking.armorEquipmentRange = clampRange(
            this.network.tracking.armorEquipmentRange, 0, 256, "network.tracking.armor-equipment-range", logger
        );
        if (this.network.tracking.particleBroadcastRange < 0.0d || this.network.tracking.particleBroadcastRange > 256.0d || Double.isNaN(this.network.tracking.particleBroadcastRange)) {
            logger.warn("[UltraCore] Clamping network.tracking.particle-broadcast-range to [0.0, 256.0]");
            this.network.tracking.particleBroadcastRange = Math.max(0.0d, Math.min(256.0d, Double.isNaN(this.network.tracking.particleBroadcastRange) ? 16.0d : this.network.tracking.particleBroadcastRange));
        }
    }

    private static int clampRange(final int value, final int min, final int max, final String key, final Logger logger) {
        if (value < min || value > max) {
            final int clamped = Math.max(min, Math.min(max, value));
            logger.warn("[UltraCore] Clamping {}={} to [{}, {}] -> {}", key, value, min, max, clamped);
            return clamped;
        }
        return value;
    }

    @ConfigSerializable
    public static final class WorldsSection {
        @Setting("disabled-dimensions")
        @Comment("Dimensions skipped before ServerLevel allocation (e.g. minecraft:the_nether) | Виміри, що не завантажуються і не алокуються в пам'яті")
        public List<String> disabledDimensions = new ArrayList<>();

        @Setting("disable-custom-worlds")
        @Comment("Block plugins from creating or loading extra worlds at runtime | Заборонити плагінам створювати додаткові світи в рантаймі")
        public boolean disableCustomWorlds = false;

        @Setting("disable-portals")
        @Comment("Disable Nether/End portal dimension transitions | Вимкнути переходи гравців та сутностей через портали")
        public boolean disablePortals = false;

        @Setting("disabled-ticking-dimensions")
        @Comment("Dimensions that load without ticking entities or chunks | Виміри, що завантажуються без тіків сутностей і чанків")
        public List<String> disabledTickingDimensions = new ArrayList<>();
    }

    @ConfigSerializable
    public static final class EntitiesSection {
        @Setting("ai")
        @Comment("Entity AI, brains, goals & collision switches | Вимикачі ШІ, мозку, цілей та колізій сутностей")
        public AiSection ai = new AiSection();
    }

    @ConfigSerializable
    public static final class AiSection {
        @Setting("disable-globally")
        @Comment("Disable all mob AI execution and goal registration globally | Повністю вимкнути ШІ та реєстрацію цілей для всіх мобів")
        public boolean disableGlobally = false;

        @Setting("disabled-types")
        @Comment("Entity types with AI disabled (e.g. minecraft:villager) | Типи сутностей, для яких вимкнено ШІ")
        public List<String> disabledTypes = new ArrayList<>();

        @Setting("disable-goal-selector")
        @Comment("Skip GoalSelector & targetSelector ticking | Пропускати тіки GoalSelector та targetSelector")
        public boolean disableGoalSelector = false;

        @Setting("disable-brain")
        @Comment("Skip Brain ticking and initialization for Brain-based mobs | Пропускати тіки та ініціалізацію Brain у мобів")
        public boolean disableBrain = false;

        @Setting("disable-pathfinding")
        @Comment("Disable PathNavigation path recalculation and movement ticks | Вимкнути пошук шляхів і навігацію мобів")
        public boolean disablePathfinding = false;

        @Setting("disable-sensors")
        @Comment("Disable mob Sensing line-of-sight and brain sensors | Вимкнути сенсори видимості та датчики мозку мобів")
        public boolean disableSensors = false;

        @Setting("disable-collisions")
        @Comment("Disable all entity-to-entity push collisions | Вимкнути фізичне зіштовхування між усіма сутностями")
        public boolean disableCollisions = false;
    }

    @ConfigSerializable
    public static final class ChunksSection {
        @Setting("disable-structures")
        @Comment("Skip structure generation during chunk creation | Вимкнути генерацію структур при створенні чанків")
        public boolean disableStructures = false;

        @Setting("disable-biome-decoration")
        @Comment("Skip ore, tree and biome feature decoration | Вимкнути генерацію руд, дерев та декорацій біомів")
        public boolean disableBiomeDecoration = false;

        @Setting("disable-chunk-ticking")
        @Comment("Skip ServerChunkCache chunk ticking loop | Вимкнути цикл тіків чанків у ServerChunkCache")
        public boolean disableChunkTicking = false;

        @Setting("disable-random-ticks")
        @Comment("Skip random block ticks (crops, grass, ice) | Вимкнути випадкові тіки блоків (ріст рослин, лід)")
        public boolean disableRandomTicks = false;

        @Setting("disable-block-ticks")
        @Comment("Skip scheduled block ticks | Вимкнути заплановані тіки блоків")
        public boolean disableBlockTicks = false;

        @Setting("disable-fluid-ticks")
        @Comment("Skip scheduled water and lava fluid ticks | Вимкнути тіки рідин (води та лави)")
        public boolean disableFluidTicks = false;
    }

    @ConfigSerializable
    public static final class GameplaySection {
        @Setting("disable-weather")
        @Comment("Disable rain, thunder and precipitation cycles | Вимкнути зміну погоди, дощ, грози та снігопад")
        public boolean disableWeather = false;

        @Setting("disable-raids")
        @Comment("Disable village raids ticking and creation | Вимкнути рейди на села та їх тікінг")
        public boolean disableRaids = false;

        @Setting("disable-redstone")
        @Comment("Disable redstone neighbor updates and wire evaluation | Вимкнути оновлення редстоуну та сигналів")
        public boolean disableRedstone = false;

        @Setting("disable-natural-spawning")
        @Comment("Disable natural mob spawning in loaded chunks | Вимкнути природний спавн мобів у чанках")
        public boolean disableNaturalSpawning = false;

        @Setting("disable-custom-spawners")
        @Comment("Skip phantom, patrol, cat, siege and wandering trader spawners | Вимкнути фантомів, патрулі, котів, облоги та торговців")
        public boolean disableCustomSpawners = false;

        @Setting("disable-monsters")
        @Comment("Disable natural monster category spawning (replaces spawn-monsters) | Вимкнути природний спавн монстрів")
        public boolean disableMonsters = false;

        @Setting("disable-animals")
        @Comment("Disable natural animal & ambient category spawning (replaces spawn-animals) | Вимкнути природний спавн тварин та мирних мобів")
        public boolean disableAnimals = false;
    }

    @ConfigSerializable
    public static final class NetworkSection {
        @Setting("analytics")
        @Comment("Telemetry, profiler & debug sync switches | Вимикачі метрик, профайлера та дебаг-синхронізації")
        public AnalyticsSection analytics = new AnalyticsSection();

        @Setting("tracking")
        @Comment("Player collisions, packet range filtering & ping switches | Вимикачі колізій гравців, фільтрації пакетів та пінгу")
        public TrackingSection tracking = new TrackingSection();
    }

    @ConfigSerializable
    public static final class AnalyticsSection {
        @Setting("disable-metrics-and-profiler")
        @Comment("Disable bStats metrics, Spark auto-init and activity monitor | Вимкнути метрики bStats, Spark та монітор активності")
        public boolean disableMetricsAndProfiler = false;

        @Setting("disable-debug-synchronizers")
        @Comment("Disable server debug synchronizer ticks | Вимкнути тіки серверних дебаг-синхронізаторів")
        public boolean disableDebugSynchronizers = false;
    }

    @ConfigSerializable
    public static final class TrackingSection {
        @Setting("disable-player-collisions")
        @Comment("Disable player-to-player pushing in lobbies and PvP | Вимкнути штовхання між гравцями для лобі та PvP")
        public boolean disablePlayerCollisions = false;

        @Setting("fast-packet-filtering")
        @Comment("Cap projectile, particle & equipment broadcast ranges | Урізати дистанцію відправки снарядів, партиклів і броні")
        public boolean fastPacketFiltering = false;

        @Setting("projectile-tracking-range")
        @Comment("Max block range for tracking projectiles when filtering is active | Макс. дистанція трекінгу снарядів (в блоках)")
        public int projectileTrackingRange = 32;

        @Setting("particle-broadcast-range")
        @Comment("Max block range for particle packets when filtering is active | Макс. дистанція відправки пакетів партиклів")
        public double particleBroadcastRange = 16.0d;

        @Setting("armor-equipment-range")
        @Comment("Max block range for entity equipment updates when filtering is active | Макс. дистанція пакетів екіпірування та броні")
        public int armorEquipmentRange = 32;

        @Setting("disable-server-ping-sample")
        @Comment("Omit player list sample in MOTD status ping responses | Прибрати список гравців з MOTD/пінг-пакетів для балансувальника")
        public boolean disableServerPingSample = false;
    }

    @ConfigSerializable
    public static final class StorageSection {
        @Setting("ephemeral")
        @Comment("Ephemeral Docker container & zero-I/O world storage switches | Вимикачі ефемерного сховища для Docker-контейнерів")
        public EphemeralStorageSection ephemeral = new EphemeralStorageSection();
    }

    @ConfigSerializable
    public static final class EphemeralStorageSection {
        @Setting("memory-only-world")
        @Comment("Disable chunk, region and level.dat disk writes for ephemeral arenas | Повністю вимкнути запис чанків та level.dat на диск (0 I/O)")
        public boolean memoryOnlyWorld = false;

        @Setting("fast-shutdown")
        @Comment("Instant container stop on SIGTERM without flushing regions or thread pools | Миттєве вимкнення по SIGTERM без очікування флашу чанків")
        public boolean fastShutdown = false;
    }

    @ConfigSerializable
    public static final class PlayersSection {
        @Setting("lifecycle")
        @Comment("Player save, advancements & recipe book switches | Вимикачі збереження гравців, досягнень та книги рецептів")
        public PlayerLifecycleSection lifecycle = new PlayerLifecycleSection();
    }

    @ConfigSerializable
    public static final class PlayerLifecycleSection {
        @Setting("disable-player-data-saving")
        @Comment("Skip writing playerdata .dat files to disk | Вимкнути збереження .dat файлів гравців на диск")
        public boolean disablePlayerDataSaving = false;

        @Setting("disable-advancements")
        @Comment("Skip loading, saving and triggering player advancements | Вимкнути завантаження, збереження та тригери досягнень")
        public boolean disableAdvancements = false;

        @Setting("disable-recipes")
        @Comment("Skip server recipe book syncing, loading and ticking | Вимкнути книгу рецептів сервера та її синхронізацію")
        public boolean disableRecipes = false;
    }

    @ConfigSerializable
    public static final class WorldSection {
        @Setting("physics")
        @Comment("Block gravity, hoppers, leaves & fire physics switches | Вимикачі гравітації блоків, воронок, листя та вогню")
        public PhysicsSection physics = new PhysicsSection();
    }

    @ConfigSerializable
    public static final class PhysicsSection {
        @Setting("disable-block-gravity")
        @Comment("Disable falling sand, gravel and anvil gravity | Вимкнути падіння піску, гравію та ковадл")
        public boolean disableBlockGravity = false;

        @Setting("disable-hopper-ticking")
        @Comment("Disable hopper block-entity item transfer ticking | Повністю вимкнути тіки воронок (HopperBlockEntity)")
        public boolean disableHopperTicking = false;

        @Setting("disable-leaf-decay")
        @Comment("Disable natural leaf decay random ticks | Вимкнути опадання листя")
        public boolean disableLeafDecay = false;

        @Setting("disable-fire-spread")
        @Comment("Disable fire spread and burn ticking | Вимкнути поширення та горіння вогню")
        public boolean disableFireSpread = false;
    }
}
