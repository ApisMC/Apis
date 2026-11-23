// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.config;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apismc.apis.feature.FeatureRegistry;
import org.apismc.apis.hooks.ApisLog;
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
 * Positive-logic YAML configuration for Apis capabilities (all comments in ENG | UA).
 * Setting a flag to {@code true} keeps standard Vanilla/Paper behavior active; setting {@code false}
 * activates the O(1) kill-switch and cuts out the subsystem cleanly.
 */
@ConfigSerializable
public final class ApisConfig {

    public static final String DEFAULT_FILE_NAME = "apis.yml";
    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_VANILLA = "vanilla";
    public static final String DEFAULT_FALLBACK_WORLD = "hub";
    public static final List<String> DEFAULT_ENABLED_DIMENSIONS = List.of(
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    );
    public static final List<String> DEFAULT_ALWAYS_TICKING_DIMENSIONS = List.of("minecraft:overworld");

    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");
    private static final String HEADER = """
        Apis Feature Matrix | Позитивна матриця модулів Apis
        true = standard Vanilla/Paper behavior enabled | true = стандартна механіка увімкнена
        false = subsystem killed for maximum performance & zero overhead | false = підсистема вирізана для оптимізації
        """;

    private static volatile ApisConfig instance = new ApisConfig();
    private static volatile Path configPath = Path.of(ApisPaths.DEFAULT_CONFIGS_DIR, DEFAULT_FILE_NAME);

    @Setting("startup")
    @Comment("Bootstrap, discovery & validation switches at cold boot | Прапори холодного старту, перевірок та завантаження датапаків")
    public StartupSection startup = new StartupSection();

    @Setting("caches")
    @Comment("Eager vs lazy cache initialization strategies | Стратегії попереднього прогріву кешів ядра")
    public CachesSection caches = new CachesSection();

    @Setting("dimensions")
    @Comment("Dimension whitelists, runtime world creation & portals | Білі списки вимірів, створення світів та портали")
    public DimensionsSection dimensions = new DimensionsSection();

    @Setting("chunks")
    @Comment("Chunk generation & chunk-ticking hierarchy | Генерація чанків та ієрархія їх тікінгу")
    public ChunksSection chunks = new ChunksSection();

    @Setting("block-entities")
    @Comment("Tile/block-entity ticking controls | Керування тікінгом тайл-ентіті (блоків-сутностей)")
    public BlockEntitiesSection blockEntities = new BlockEntitiesSection();

    @Setting("blocks-and-physics")
    @Comment("Block behavior, redstone & physical world simulation | Фізика блоків, редстоун та падіння предметів")
    public BlocksAndPhysicsSection blocksAndPhysics = new BlocksAndPhysicsSection();

    @Setting("world-environment")
    @Comment("Time, weather, raids, sleep & dragon fight mechanics | Час доби, погода, рейди, сон та бій із драконом")
    public WorldEnvironmentSection worldEnvironment = new WorldEnvironmentSection();

    @Setting("spawning")
    @Comment("Natural mob spawning & overworld custom spawners | Природний спавн мобів та спеціальні спавнери")
    public SpawningSection spawning = new SpawningSection();

    @Setting("entities")
    @Comment("Entity AI subsystems & collision handling | Інтелект мобів (AI) та фізичні колізії сутностей")
    public EntitiesSection entities = new EntitiesSection();

    @Setting("gameplay")
    @Comment("Datapack progression, recipes, loot & functions | Досягнення, рецепти, таблиці луту та функції датапаків")
    public GameplaySection gameplay = new GameplaySection();

    @Setting("players")
    @Comment("Per-player persistence, vital mechanics & stats | Збереження гравців, голод, статистика та трекер вардена")
    public PlayersSection players = new PlayersSection();

    @Setting("network")
    @Comment("Crypto, telemetry, player lists & packet range clamping | Мережа, телеметрія, списки гравців і фільтрація пакетів")
    public NetworkSection network = new NetworkSection();

    @Setting("storage")
    @Comment("Disk persistence for worlds & chunk regions | Запис світів і чанків на диск (false = режим лише в RAM)")
    public StorageSection storage = new StorageSection();

    @Setting("system")
    @Comment("JVM process lifecycle & shutdown behavior | Життєвий цикл процесу сервера та вимкнення")
    public SystemSection system = new SystemSection();

    @Setting("compatibility")
    @Comment("Legacy Bukkit/Mojang compatibility subsystems | Застарілі механізми сумісності зі старими плагінами/даними")
    public CompatibilitySection compatibility = new CompatibilitySection();

    @Setting("messages")
    @Comment("MiniMessage localization templates for built-in core commands (set '' to mute) | Шаблони MiniMessage для команд ядра ('' вимикає вивід)")
    public MessagesSection messages = new MessagesSection();

    public static ApisConfig get() {
        return instance;
    }

    public static Path configPath() {
        return configPath;
    }

    /**
     * Resets static config singleton state between unit tests.
     */
    public static void resetForTesting() {
        instance = new ApisConfig();
        configPath = Path.of(ApisPaths.DEFAULT_CONFIGS_DIR, DEFAULT_FILE_NAME);
    }

    /**
     * Loads config/apis.yml as early as possible (before Minecraft Bootstrap / DataFixers / world allocation).
     *
     * @param path path to the YAML configuration file
     * @return loaded and sanitized configuration instance
     */
    public static synchronized ApisConfig loadEarly(final Path path) {
        configPath = path;
        final ApisConfig loaded = loadOrCreate(path, LOGGER);
        loaded.sanitize(LOGGER);
        instance = loaded;
        FeatureRegistry.applyEarly(loaded);
        ApisLog.ok(
            LOGGER,
            "Loaded {} (active kill-switches: {})",
            path.normalize(),
            FeatureRegistry.activeKillSwitches().size()
        );
        return loaded;
    }

    /**
     * Reloads configuration from disk and refreshes O(1) bitmasks + registry sets.
     *
     * @return reloaded configuration instance
     */
    public static synchronized ApisConfig reload() {
        return loadEarly(configPath);
    }

    static ApisConfig loadOrCreate(final Path path, final Logger logger) {
        try {
            final Path parent = path.toAbsolutePath().getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (final IOException ex) {
            logger.error("Cannot create parent directory for {}. Using safe defaults.", path, ex);
            return new ApisConfig();
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
            final Set<String> keysBefore = existed ? collectNodePaths(root) : Set.of();
            final @Nullable ApisConfig cfg = root.get(ApisConfig.class);
            final ApisConfig effective = cfg != null ? cfg : new ApisConfig();
            final boolean missingOrEmpty = !existed || cfg == null;
            if (missingOrEmpty) {
                root.set(ApisConfig.class, effective);
                saveWithComments(path, loader, root);
            } else {
                root.set(ApisConfig.class, effective);
                final Set<String> keysAfter = collectNodePaths(root);
                if (!keysBefore.containsAll(keysAfter)) {
                    saveWithComments(path, loader, root);
                }
            }
            return effective;
        } catch (final IOException ex) {
            logger.error("Failed to parse {}. Backing up broken config and using defaults.", path, ex);
            backupBrokenConfig(path, logger);
            final ApisConfig fallback = new ApisConfig();
            try {
                final CommentedConfigurationNode root = loader.createNode();
                root.set(ApisConfig.class, fallback);
                saveWithComments(path, loader, root);
            } catch (final IOException saveEx) {
                logger.warn("Could not write default config to {}", path, saveEx);
            }
            return fallback;
        }
    }

    /**
     * Collects dotted node paths for all keys inside a loaded Configurate tree.
     *
     * @param root configuration root node
     * @return set of dotted key paths
     */
    public static Set<String> collectNodePaths(final CommentedConfigurationNode root) {
        final Set<String> paths = new HashSet<>();
        collectNodePaths("", root, paths);
        return paths;
    }

    private static void collectNodePaths(
        final String prefix,
        final CommentedConfigurationNode node,
        final Set<String> out
    ) {
        if (!node.isMap()) {
            if (!prefix.isEmpty()) {
                out.add(prefix);
            }
            return;
        }
        for (final Map.Entry<Object, CommentedConfigurationNode> entry : node.childrenMap().entrySet()) {
            final String segment = String.valueOf(entry.getKey());
            final String nextPrefix = prefix.isEmpty() ? segment : prefix + '.' + segment;
            out.add(nextPrefix);
            collectNodePaths(nextPrefix, entry.getValue(), out);
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

    /**
     * Injects line comments defined via {@link Comment} annotations into serialized YAML text.
     *
     * @param rawYaml raw YAML emitted by Configurate
     * @param root    annotated Configurate root node
     * @return YAML with rendered comments above matching keys
     */
    public static String renderNodeComments(final String rawYaml, final CommentedConfigurationNode root) {
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
            logger.warn("Could not back up malformed config {}", path, ex);
        }
    }

    void sanitize(final Logger logger) {
        if (this.startup == null) this.startup = new StartupSection();
        if (this.caches == null) this.caches = new CachesSection();
        if (this.dimensions == null) this.dimensions = new DimensionsSection();
        if (this.dimensions.enabled == null) this.dimensions.enabled = new ArrayList<>(DEFAULT_ENABLED_DIMENSIONS);
        if (this.dimensions.alwaysTicking == null) {
            this.dimensions.alwaysTicking = new ArrayList<>(DEFAULT_ALWAYS_TICKING_DIMENSIONS);
        }
        if (this.dimensions.neverTicking == null) this.dimensions.neverTicking = new ArrayList<>();
        if (this.chunks == null) this.chunks = new ChunksSection();
        if (this.chunks.ticking == null) this.chunks.ticking = new ChunkTickingSection();
        if (this.chunks.ticking.subFeatures == null) this.chunks.ticking.subFeatures = new ChunkTickingSubFeaturesSection();
        if (this.blockEntities == null) this.blockEntities = new BlockEntitiesSection();
        if (this.blockEntities.ticking == null) this.blockEntities.ticking = new BlockEntityTickingSection();
        if (this.blockEntities.ticking.subFeatures == null) {
            this.blockEntities.ticking.subFeatures = new BlockEntityTickingSubFeaturesSection();
        }
        if (this.blocksAndPhysics == null) this.blocksAndPhysics = new BlocksAndPhysicsSection();
        if (this.worldEnvironment == null) this.worldEnvironment = new WorldEnvironmentSection();
        if (this.spawning == null) this.spawning = new SpawningSection();
        if (this.spawning.natural == null) this.spawning.natural = new NaturalSpawningSection();
        if (this.spawning.natural.subFeatures == null) this.spawning.natural.subFeatures = new NaturalSpawningSubFeaturesSection();
        if (this.entities == null) this.entities = new EntitiesSection();
        if (this.entities.ai == null) this.entities.ai = new AiSection();
        if (this.entities.ai.typeFilter == null) this.entities.ai.typeFilter = new ArrayList<>();
        if (this.entities.ai.subFeatures == null) this.entities.ai.subFeatures = new AiSubFeaturesSection();
        if (this.entities.collisions == null) this.entities.collisions = new CollisionsSection();
        if (this.gameplay == null) this.gameplay = new GameplaySection();
        if (this.players == null) this.players = new PlayersSection();
        if (this.network == null) this.network = new NetworkSection();
        if (this.network.analytics == null) this.network.analytics = new AnalyticsSection();
        if (this.network.playerList == null) this.network.playerList = new PlayerListSection();
        if (this.network.packetRangeClamping == null) this.network.packetRangeClamping = new PacketRangeClampingSection();
        if (this.storage == null) this.storage = new StorageSection();
        if (this.system == null) this.system = new SystemSection();
        if (this.system.lifecycle == null) this.system.lifecycle = new SystemLifecycleSection();
        if (this.compatibility == null) this.compatibility = new CompatibilitySection();
        if (this.compatibility.legacy == null) this.compatibility.legacy = new LegacySection();
        if (this.messages == null) this.messages = new MessagesSection();
        if (this.messages.commands == null) this.messages.commands = new CommandMessagesSection();
        this.messages.commands.fillMissingDefaults();

        final String rawMode = this.dimensions.mode == null ? MODE_STANDALONE : this.dimensions.mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_STANDALONE.equals(rawMode) && !MODE_VANILLA.equals(rawMode)) {
            logger.warn("Invalid dimensions.mode '{}'; defaulting to '{}'.", this.dimensions.mode, MODE_STANDALONE);
            this.dimensions.mode = MODE_STANDALONE;
        } else {
            this.dimensions.mode = rawMode;
        }

        final String rawFallback = this.dimensions.fallbackWorld == null ? "" : this.dimensions.fallbackWorld.trim();
        if (rawFallback.isEmpty() || rawFallback.contains("/") || rawFallback.contains("\\") || rawFallback.contains("..")) {
            logger.warn("Invalid dimensions.fallback-world '{}'; defaulting to '{}'.", this.dimensions.fallbackWorld, DEFAULT_FALLBACK_WORLD);
            this.dimensions.fallbackWorld = DEFAULT_FALLBACK_WORLD;
        } else {
            this.dimensions.fallbackWorld = rawFallback;
        }

        this.network.packetRangeClamping.projectileTrackingRange = clampInt(
            this.network.packetRangeClamping.projectileTrackingRange,
            0,
            256,
            "network.packet-range-clamping.projectile-tracking-range",
            logger
        );
        this.network.packetRangeClamping.armorEquipmentRange = clampInt(
            this.network.packetRangeClamping.armorEquipmentRange,
            0,
            256,
            "network.packet-range-clamping.armor-equipment-range",
            logger
        );
        if (Double.isNaN(this.network.packetRangeClamping.particleBroadcastRange)
            || this.network.packetRangeClamping.particleBroadcastRange < 0.0d
            || this.network.packetRangeClamping.particleBroadcastRange > 256.0d) {
            final double clamped = Double.isNaN(this.network.packetRangeClamping.particleBroadcastRange)
                ? 16.0d
                : Math.max(0.0d, Math.min(256.0d, this.network.packetRangeClamping.particleBroadcastRange));
            logger.warn(
                "Clamping network.packet-range-clamping.particle-broadcast-range={} to [0.0, 256.0] -> {}",
                this.network.packetRangeClamping.particleBroadcastRange,
                clamped
            );
            this.network.packetRangeClamping.particleBroadcastRange = clamped;
        }
    }

    private static int clampInt(final int val, final int min, final int max, final String key, final Logger logger) {
        if (val < min || val > max) {
            final int clamped = Math.max(min, Math.min(max, val));
            logger.warn("Clamping {}={} to [{}, {}] -> {}", key, val, min, max, clamped);
            return clamped;
        }
        return val;
    }

    @ConfigSerializable
    public static final class StartupSection {
        @Setting("datafixers")
        @Comment("Build DFU/DataConverter schemas & migrate old level/item formats; false = skip DFU completely (fast boot, breaks pre-26.3 world upgrades) | Будувати схеми DFU/DataConverter та мігрувати старі світи; false = пропустити ініціалізацію DFU для прискорення старту")
        public boolean datafixers = true;

        @Setting("search-spawn-point")
        @Comment("Scan 11x11 spawn chunks for a safe surface coordinate; false = instant deterministic spawn at (0, seaLevel, 0) | Шукати безпечний спавн у сітці 11x11 чанків; false = миттєво встановлювати спавн на (0, seaLevel, 0)")
        public boolean searchSpawnPoint = true;

        @Setting("initial-chunk-load")
        @Comment("Preload and wait for spawn ticketed chunks before Done; false = finish boot immediately without waiting for spawn chunks | Передзавантажувати й чекати стартові чанки до Done; false = пропустити цикл очікування чанків")
        public boolean initialChunkLoad = true;

        @Setting("mojang-service-discovery")
        @Comment("Query discovery.minecraftservices.com at boot; false = offline stub with zero HTTP/DNS wait (~1.5-2s faster without internet) | Запитувати сервіси Mojang під час запуску; false = офлайн-заглушка без затримок мережі")
        public boolean mojangServiceDiscovery = true;

        @Setting("paper-version-check")
        @Comment("Check Paper/GitHub API for newer builds on startup; false = skip version update query | Перевіряти оновлення збірки на старті; false = вимкнути мережеву перевірку версії")
        public boolean paperVersionCheck = true;

        @Setting("validate-creative-tabs")
        @Comment("Validate CreativeModeTabs & LootContextParamSets in Bootstrap; false = skip startup consistency checks | Валідувати вкладки креативу та набори контекстів у Bootstrap; false = пропустити перевірки")
        public boolean validateCreativeTabs = true;

        @Setting("validate-loot")
        @Comment("Run ReloadableServerRegistries loot validation after datapack load; false = skip loot reference validation | Перевіряти цілісність таблиць луту після завантаження датапаків; false = пропустити валідацію луту")
        public boolean validateLoot = true;

        @Setting("test-registries")
        @Comment("Load GameTest datapack registries (test_environment, test_instance); false = prune them before disk/JSON parsing | Завантажувати тестові реєстри датапаків (test_environment, test_instance); false = вирізати до читання з диска")
        public boolean testRegistries = true;
    }

    @ConfigSerializable
    public static final class CachesSection {
        @Setting("eager-blockstate-cache")
        @Comment("Precompute collision, occlusion & light caches for all ~27k+ BlockStates at boot; false = calculate lazily on first access per state | Прогрівати кеші колізій та оклюзії всіх станів блоків на старті; false = лінивий розрахунок при першому зверненні")
        public boolean eagerBlockstateCache = true;

        @Setting("eager-craftblockdata-cache")
        @Comment("Pre-populate CraftBlockData caches & clones on class init; false = allocate & parse lazily on demand | Попередньо заповнювати кеш CraftBlockData при старті; false = ліниве кешування за вимогою")
        public boolean eagerCraftblockdataCache = true;

        @Setting("eager-map-color-cache")
        @Comment("Allocate & build the 16 MB CraftMapColorCache lookup table at startup; false = match map colors lazily on demand (saves 16 MB RAM) | Будувати 16 МБ таблицю кольорів карт на старті; false = лінивий підбір без виділення буфера")
        public boolean eagerMapColorCache = true;
    }

    @ConfigSerializable
    public static final class DimensionsSection {
        @Setting("mode")
        @Comment("World architecture mode: 'standalone' (isolated worlds/ folder, Void generator, world.yml spawns) or 'vanilla' (standard Paper root worlds & terrain) | Режим світів: 'standalone' (ізольована тека worlds/, чистий Void, world.yml) або 'vanilla' (стандартний Paper)")
        public String mode = MODE_STANDALONE;

        @Setting("fallback-world")
        @Comment("Primary world name inside worlds/ used when worlds/ is empty or as default join world in standalone mode | Ім'я базового світу в теці worlds/ при порожній теці або стандартному вході")
        public String fallbackWorld = DEFAULT_FALLBACK_WORLD;

        @Setting("enabled")
        @Comment("Positive whitelist of dimensions allowed to load (minecraft:overworld is always protected and loaded; remove the_nether/the_end to skip allocation) | Позитивний білий список вимірів, дозволених до завантаження (minecraft:overworld завжди захищений і завантажується)")
        public List<String> enabled = new ArrayList<>(DEFAULT_ENABLED_DIMENSIONS);

        @Setting("always-ticking")
        @Comment("Dimensions that always tick even with 0 players online (other worlds tick dynamically only while players are inside) | Виміри, що тікають завжди навіть без гравців (інші світи сплять при 0 гравців)")
        public List<String> alwaysTicking = new ArrayList<>(DEFAULT_ALWAYS_TICKING_DIMENSIONS);

        @Setting("never-ticking")
        @Comment("Dimensions that never tick even when players are inside (overrides always-ticking and dynamic player-presence ticking) | Виміри, що не тікають ніколи навіть із гравцями (має пріоритет над always-ticking)")
        public List<String> neverTicking = new ArrayList<>();

        @Setting("custom-worlds")
        @Comment("Allow plugins to create or load extra worlds at runtime via CraftServer.createWorld; false = block runtime world creation | Дозволити плагінам створювати або завантажувати додаткові світи; false = заблокувати створення світів")
        public boolean customWorlds = true;

        @Setting("portals")
        @Comment("Allow Nether and End portal entity transitions; false = cancel portal teleportation completely | Дозволити телепортацію через портали Незера й Енда; false = повністю вимкнути переходи порталами")
        public boolean portals = true;
    }

    @ConfigSerializable
    public static final class ChunksSection {
        @Setting("structures")
        @Comment("Load structure registries, generate world structures & allow /locate structure; false = prune structure registries at boot and skip structure placement | Завантажувати та генерувати структури світу (і пошук структур); false = вирізати реєстри структур і їх генерацію")
        public boolean structures = true;

        @Setting("biome-decoration")
        @Comment("Load feature/carver registries and generate ores, trees & features; false = use slim NETWORK_CODEC for biomes and skip decoration | Генерувати руди, дерева й декорації біомів; false = полегшений декодер біомів без читання фіч на старті")
        public boolean biomeDecoration = true;

        @Setting("ticking")
        @Comment("Master chunk ticking controller and per-subsystem tick flags | Головний контролер тікінгу чанків та дочірні підсистеми")
        public ChunkTickingSection ticking = new ChunkTickingSection();
    }

    @ConfigSerializable
    public static final class ChunkTickingSection {
        @Setting("enabled")
        @Comment("[MASTER SWITCH] false = cuts chunk ticking completely in ServerChunkCache; sub-features below are bypassed | [ГОЛОВНИЙ РУБИЛЬНИК] false = повністю зупиняє цикл тіків чанків; параметри нижче ігноруються")
        public boolean enabled = true;

        @Setting("sub-features")
        @Comment("Granular tick loops active when chunks.ticking.enabled=true | Окремі цикли тіків, активні коли chunks.ticking.enabled=true")
        public ChunkTickingSubFeaturesSection subFeatures = new ChunkTickingSubFeaturesSection();
    }

    @ConfigSerializable
    public static final class ChunkTickingSubFeaturesSection {
        @Setting("random-ticks")
        @Comment("Tick random block updates (crop growth, grass spread, ice melt); false = freeze random ticks | Виконувати випадкові тіки блоків (ріст рослин, трава, танення льоду); false = заморозити")
        public boolean randomTicks = true;

        @Setting("block-ticks")
        @Comment("Tick scheduled block updates in ServerLevel; false = skip block tick queue | Виконувати заплановані тіки блоків; false = пропускати чергу запланованих оновлень блоків")
        public boolean blockTicks = true;

        @Setting("fluid-ticks")
        @Comment("Tick scheduled water/lava flow updates in ServerLevel; false = freeze all fluid flow | Виконувати тіки рідин (води та лави); false = зупинити розтікання рідин")
        public boolean fluidTicks = true;

        @Setting("poi-ticking")
        @Comment("Tick Point-of-Interest (POI) spatial index updates in ChunkMap; false = skip PoiManager#tick when villages/bees/portals are unused | Тікати просторовий індекс POI у ChunkMap; false = пропускати PoiManager#tick")
        public boolean poiTicking = true;
    }

    @ConfigSerializable
    public static final class BlockEntitiesSection {
        @Setting("ticking")
        @Comment("Master block-entity ticking controller and specific exemptions | Головний контролер тікінгу тайл-ентіті та окремі підтипи")
        public BlockEntityTickingSection ticking = new BlockEntityTickingSection();
    }

    @ConfigSerializable
    public static final class BlockEntityTickingSection {
        @Setting("enabled")
        @Comment("[MASTER SWITCH] false = cuts all block-entity ticking (furnaces, beacons, chests, hoppers); sub-features below are bypassed | [ГОЛОВНИЙ РУБИЛЬНИК] false = вимикає тікінг усіх тайл-ентіті (печі, маяки, воронки); параметри нижче ігноруються")
        public boolean enabled = true;

        @Setting("sub-features")
        @Comment("Granular block-entity controls active when block-entities.ticking.enabled=true | Точкові вимкнення тайл-ентіті, коли block-entities.ticking.enabled=true")
        public BlockEntityTickingSubFeaturesSection subFeatures = new BlockEntityTickingSubFeaturesSection();
    }

    @ConfigSerializable
    public static final class BlockEntityTickingSubFeaturesSection {
        @Setting("hoppers")
        @Comment("Tick HopperBlockEntity item transfers; false = freeze hopper pull/push logic while keeping other block-entities active | Тікати перенесення предметів воронками; false = заморозити лише воронки, не чіпаючи інші тайл-ентіті")
        public boolean hoppers = true;
    }

    @ConfigSerializable
    public static final class BlocksAndPhysicsSection {
        @Setting("redstone")
        @Comment("Evaluate RedstoneWireBlock power & neighbor updates; false = disable redstone wire signal propagation | Обчислювати редстоун-сигнал та оновлення дротів; false = вимкнути логіку редстоун-пилу")
        public boolean redstone = true;

        @Setting("block-gravity")
        @Comment("Allow sand, gravel & anvils to fall; false = skip FallingBlock physics ticks | Дозволити падіння піску, гравію та ковадл; false = вимкнути гравітацію блоків")
        public boolean blockGravity = true;

        @Setting("leaf-decay")
        @Comment("Allow natural leaf decay random ticks; false = keep leaves permanent without wood nearby | Дозволити природне опадання листя; false = листя ніколи не зникає самостійно")
        public boolean leafDecay = true;

        @Setting("fire-spread")
        @Comment("Allow fire burning & spreading ticks; false = freeze fire spread completely | Дозволити горіння та поширення вогню; false = заблокувати поширення вогню")
        public boolean fireSpread = true;

        @Setting("item-physics")
        @Comment("Run movement, gravity & collision physics on dropped ItemEntity; false = keep pickup/despawn timers without motion physics | Прораховувати рух і колізії викинутих предметів; false = залишити лише таймер підбору/деспавну без фізики")
        public boolean itemPhysics = true;

        @Setting("block-events")
        @Comment("Queue and execute ServerLevel block events (pistons, note blocks, chest animations); false = skip ServerLevel#runBlockEvents | Обробляти події блоків (поршні, нотні блоки, анімація скринь); false = пропустити runBlockEvents")
        public boolean blockEvents = true;
    }

    @ConfigSerializable
    public static final class WorldEnvironmentSection {
        @Setting("daylight-cycle")
        @Comment("Tick WorldClock time, sky brightness & SetTime broadcasts; false = freeze world time with zero packet spam | Рухати цикл дня/ночі та розсилати пакети часу; false = заморозити час доби без мережевого спаму")
        public boolean daylightCycle = true;

        @Setting("weather")
        @Comment("Allow rain, thunder & ice/snow accumulation; false = lock clear weather across all levels | Дозволити дощ, грозу та утворення снігу/льоду; false = зафіксувати ясну погоду")
        public boolean weather = true;

        @Setting("raids")
        @Comment("Allow village raid creation and ticking; false = skip Raids#tick | Дозволити рейди на села; false = вимкнути створення і тікінг рейдів")
        public boolean raids = true;

        @Setting("sleep-cycle")
        @Comment("Allow players sleeping in beds to skip the night; false = disable ServerLevel sleep night-skip checks | Дозволити пропуск ночі через сон у ліжку; false = вимкнути перевірки сну в ServerLevel")
        public boolean sleepCycle = true;

        @Setting("end-dragon-fight")
        @Comment("Run EndDragonFight logic in The End; false = skip Ender Dragon fight bossbar & battle ticks | Виконувати логіку бою з Драконом Края в Енді; false = пропустити тіки EndDragonFight")
        public boolean endDragonFight = true;

        @Setting("world-border-tick")
        @Comment("Tick world border interpolation in ServerLevel and player client borders; false = skip WorldBorder#tick per tick | Тікати зміну кордонів світу в ServerLevel і гравців; false = пропустити WorldBorder#tick")
        public boolean worldBorderTick = true;

        @Setting("environment-attributes")
        @Comment("Invalidate and recompute EnvironmentAttributeSystem tick cache each tick; false = freeze environment attribute updates | Оновлювати кеш атрибутів середовища щотиково; false = заморозити EnvironmentAttributeSystem")
        public boolean environmentAttributes = true;

        @Setting("game-events")
        @Comment("Dispatch GameEvent vibrations (sculk sensors, warden, allays) on steps/actions; false = skip GameEventDispatcher#post | Розсилати вібрації GameEvent (скалк, варден, спеї); false = вимкнути GameEventDispatcher#post")
        public boolean gameEvents = true;
    }

    @ConfigSerializable
    public static final class SpawningSection {
        @Setting("natural")
        @Comment("Master natural mob-spawning controller and category filters | Головний контролер природного спавну та категорії мобів")
        public NaturalSpawningSection natural = new NaturalSpawningSection();
    }

    @ConfigSerializable
    public static final class NaturalSpawningSection {
        @Setting("enabled")
        @Comment("[MASTER SWITCH] false = cuts natural mob spawning and overworld custom spawners completely; sub-features below are bypassed | [ГОЛОВНИЙ РУБИЛЬНИК] false = повністю зупиняє природний спавн мобів і спецспавнери; параметри нижче ігноруються")
        public boolean enabled = true;

        @Setting("sub-features")
        @Comment("Granular spawning categories active when spawning.natural.enabled=true | Окремі категорії спавну, активні коли spawning.natural.enabled=true")
        public NaturalSpawningSubFeaturesSection subFeatures = new NaturalSpawningSubFeaturesSection();
    }

    @ConfigSerializable
    public static final class NaturalSpawningSubFeaturesSection {
        @Setting("monsters")
        @Comment("Allow natural spawning of MobCategory.MONSTER; false = block hostile monster natural spawns | Дозволити природний спавн ворожих монстрів; false = заборонити спавн категорії MONSTER")
        public boolean monsters = true;

        @Setting("animals")
        @Comment("Allow natural spawning of animals, ambient, water & friendly categories; false = block non-monster natural spawns | Дозволити природний спавн тварин і мирних мобів; false = заблокувати мирні категорії")
        public boolean animals = true;

        @Setting("custom-spawners")
        @Comment("Enable Phantom, Patrol, Cat, VillageSiege & WanderingTrader spawners in Overworld; false = skip registering custom spawners | Увімкнути спавнери фантомів, патрулів, котів, осад і мандрівних торговців; false = вирізати їх реєстрацію")
        public boolean customSpawners = true;
    }

    @ConfigSerializable
    public static final class EntitiesSection {
        @Setting("ai")
        @Comment("Master mob AI controller, per-type override & AI sub-features | Головний контролер ШІ мобів, фільтр типів і дочірні модулі")
        public AiSection ai = new AiSection();

        @Setting("collisions")
        @Comment("Physical pushing & entity collision controls | Налаштування фізичного зіштовхування сутностей і гравців")
        public CollisionsSection collisions = new CollisionsSection();

        @Setting("despawn-checks")
        @Comment("Run per-tick Entity#checkDespawn distance checks; false = skip vanilla despawn scans | Перевіряти умови деспавну сутностей щотіку; false = пропустити Entity#checkDespawn")
        public boolean despawnChecks = true;

        @Setting("inside-block-checks")
        @Comment("Sweep intersected blocks during entity movement (cobwebs, portals, bushes); false = skip Entity#checkInsideBlocks | Перевіряти перетин із блоками під час руху (павутина, портали, кущі); false = пропустити checkInsideBlocks")
        public boolean insideBlockChecks = true;

        @Setting("fluid-interaction")
        @Comment("Evaluate water/lava fluid height and swimming state in Entity#baseTick; false = skip fluid checks | Перевіряти контакт із водою/лавою та плавання в Entity#baseTick; false = вимкнути перевірку рідин")
        public boolean fluidInteraction = true;

        @Setting("sprint-particles")
        @Comment("Spawn server-side sprint block particles in Entity#baseTick; false = skip sprint particle broadcasts | Генерувати серверні частинки бігу в Entity#baseTick; false = вимкнути частинки бігу")
        public boolean sprintParticles = true;
    }

    @ConfigSerializable
    public static final class AiSection {
        @Setting("enabled")
        @Comment("[MASTER SWITCH] false = strips Goal allocation and Brain/AI execution from all mobs; sub-features below are bypassed | [ГОЛОВНИЙ РУБИЛЬНИК] false = повністю вирізає ШІ та цілі у всіх мобів; параметри нижче ігноруються")
        public boolean enabled = true;

        @Setting("type-filter")
        @Comment("Specific entity type IDs whose AI is suppressed even while entities.ai.enabled=true (e.g. minecraft:villager) | Список ID типів сутностей, яким ШІ вимикається індивідуально (навіть при entities.ai.enabled=true)")
        public List<String> typeFilter = new ArrayList<>();

        @Setting("sub-features")
        @Comment("Granular AI modules active when entities.ai.enabled=true | Окремі підсистеми ШІ, активні коли entities.ai.enabled=true")
        public AiSubFeaturesSection subFeatures = new AiSubFeaturesSection();
    }

    @ConfigSerializable
    public static final class AiSubFeaturesSection {
        @Setting("goal-selector")
        @Comment("Tick GoalSelector & targetSelector for mobs; false = skip goal execution ticks | Тікати GoalSelector і targetSelector у мобів; false = пропускати виконання цілей")
        public boolean goalSelector = true;

        @Setting("brain")
        @Comment("Initialize and tick mob Brain schedules & activities; false = skip Brain logic | Ініціалізувати та тікати мозок Brain; false = пропустити логіку Brain")
        public boolean brain = true;

        @Setting("pathfinding")
        @Comment("Recalculate & follow paths in PathNavigation; false = freeze mob pathfinding | Виконувати пошук і оновлення шляхів у PathNavigation; false = зупинити навігацію")
        public boolean pathfinding = true;

        @Setting("sensors")
        @Comment("Update mob Brain sensors and Sensing line-of-sight cache; false = skip sensor ticks | Оновлювати сенсори Brain та кеш видимості Sensing; false = пропустити сенсори")
        public boolean sensors = true;
    }

    @ConfigSerializable
    public static final class CollisionsSection {
        @Setting("entities")
        @Comment("[MASTER SWITCH] false = disables entity-to-entity push collisions globally (also bypasses entities.collisions.players) | [ГОЛОВНИЙ РУБИЛЬНИК] false = глобально вимикає фізичні зіштовхування між усіма сутностями (включно з гравцями)")
        public boolean entities = true;

        @Setting("players")
        @Comment("Allow player-to-player pushing collisions when entities.collisions.entities=true; false = disable player collisions & collideRule sync | Дозволити штовхання гравців між собою (коли entities.collisions.entities=true); false = вимкнути колізії гравців")
        public boolean players = true;
    }

    @ConfigSerializable
    public static final class GameplaySection {
        @Setting("advancements")
        @Comment("Load datapack advancements, track criteria & save progress; false = prune ADVANCEMENT registry at boot and stub PlayerAdvancements | Завантажувати та рахувати досягнення; false = вирізати реєстр ADVANCEMENT на старті й заглушити трекінг")
        public boolean advancements = true;

        @Setting("recipes")
        @Comment("Load recipes & sync recipe books to clients; false = prune RECIPE registry at boot and skip RecipeManager sync | Завантажувати та синхронізувати рецепти; false = вирізати реєстр RECIPE на старті й відправку клієнтам")
        public boolean recipes = true;

        @Setting("loot-tables")
        @Comment("Parse ~1100 datapack loot_table JSON definitions at boot; false = prune LOOT_TABLE registry (queries return LootTable.EMPTY) | Парсити JSON-таблиці луту з датапака; false = пропустити на старті (запити повертають LootTable.EMPTY)")
        public boolean lootTables = true;

        @Setting("command-functions")
        @Comment("Tick datapack .mcfunction schedules each server tick; false = skip ServerFunctionManager#tick | Виконувати розклад функцій .mcfunction щотіку; false = вимкнути тіки функцій датапаків")
        public boolean commandFunctions = true;
    }

    @ConfigSerializable
    public static final class PlayersSection {
        @Setting("data-saving")
        @Comment("Persist playerdata .dat files to disk (automatically bypassed when storage.world-saving=false); false = skip player disk writes | Зберігати .dat файли гравців на диск (вимикається також при storage.world-saving=false); false = не писати playerdata")
        public boolean dataSaving = true;

        @Setting("hunger")
        @Comment("Tick player FoodData (hunger, exhaustion & natural saturation regen); false = freeze hunger bar | Тікати голод і регенерацію гравців у FoodData; false = заморозити голод")
        public boolean hunger = true;

        @Setting("statistics")
        @Comment("Increment vanilla player statistics (ServerPlayer#awardStat, tick & movement stats); false = skip all vanilla stat updates | Нараховувати ванільну статистику гравцям (ServerPlayer#awardStat та рух); false = вимкнути статистику")
        public boolean statistics = true;

        @Setting("warden-tracker")
        @Comment("Tick WardenSpawnTracker per player; false = disable Warden shriek warning escalation | Тікати WardenSpawnTracker для кожного гравця; false = вимкнути накопичення попереджень Вардена")
        public boolean wardenTracker = true;

        @Setting("locator-bar")
        @Comment("Track player waypoints and WAYPOINT_TRANSMIT_RANGE crouch attributes for the 26.3 Locator Bar; false = disable Locator Bar tracking | Відстежувати вейпоїнти та атрибут WAYPOINT_TRANSMIT_RANGE для Locator Bar; false = вимкнути Locator Bar")
        public boolean locatorBar = true;

        @Setting("inventory-special-item-updates")
        @Comment("Scan all player inventory slots each tick for special items (maps, compasses); false = skip per-tick slot scan | Сканувати всі слоти інвентаря щотіку для спецпредметів (мапи); false = пропустити перевірку слотів")
        public boolean inventorySpecialItemUpdates = true;

        @Setting("vanilla-scoreboard-criteria")
        @Comment("Sync vanilla ObjectiveCriteria (health, food, air, armor, exp, level) in ServerPlayer#doTick; false = skip criteria checks (safe for TAB/custom scoreboards) | Оновлювати ванільні критерії скорборду (здоров'я, їжа, броня, рівень); false = пропустити")
        public boolean vanillaScoreboardCriteria = true;

        @Setting("floating-kick")
        @Comment("Kick players for hovering/floating too long without allowFlight; false = disable vanilla floating kick (recommended with anti-cheats or lobbies) | Кікати гравців за тривале зависання в повітрі; false = вимкнути ванільний кік за політ")
        public boolean floatingKick = true;
    }

    @ConfigSerializable
    public static final class NetworkSection {
        @Setting("generate-keypair")
        @Comment("Generate RSA encryption keypair on startup; false = skip RSA generation until needed (ideal behind Velocity/BungeeCord proxies) | Генерувати пару RSA-ключів на старті; false = пропустити генерацію за проксі або в offline-режимі")
        public boolean generateKeypair = true;

        @Setting("analytics")
        @Comment("Independent metrics, profiling & debug telemetry switches | Незалежні перемикачі метрик, профайлерів та дебаг-синхронізації")
        public AnalyticsSection analytics = new AnalyticsSection();

        @Setting("player-list")
        @Comment("MOTD server ping sample & Tab latency packet controls | Налаштування списку гравців у MOTD та пакетів пінгу в Tab")
        public PlayerListSection playerList = new PlayerListSection();

        @Setting("packet-range-clamping")
        @Comment("Optional distance clamping for noisy broadcast packets | Обмеження дальності розсилки частих пакетів (снаряди, партикли, броня)")
        public PacketRangeClampingSection packetRangeClamping = new PacketRangeClampingSection();
    }

    @ConfigSerializable
    public static final class AnalyticsSection {
        @Setting("bstats")
        @Comment("Start Apis bStats metrics collector (serviceId 35466) on boot; false = disable bStats telemetry completely | Запускати збірник метрик Apis bStats (serviceId 35466) на старті; false = вимкнути bStats")
        public boolean bstats = true;

        @Setting("spark")
        @Comment("Enable bundled Spark profiler, /spark command & tick hooks; false = suppress Spark init, banner and per-tick callbacks | Увімкнути вбудований профайлер Spark; false = повністю пропустити ініціалізацію й тіки Spark")
        public boolean spark = true;

        @Setting("tick-monitor")
        @Comment("Tick MinecraftServer activity/JFR tick monitor; false = skip serverActivityMonitor#tick per server tick | Оновлювати монітор активності тіків сервера; false = пропустити serverActivityMonitor#tick")
        public boolean tickMonitor = true;

        @Setting("debug-synchronizers")
        @Comment("Tick ServerLevel debug synchronizer broadcasts; false = skip debugSynchronizers#tick | Тікати дебаг-синхронізатори світу; false = пропустити debugSynchronizers#tick")
        public boolean debugSynchronizers = true;
    }

    @ConfigSerializable
    public static final class PlayerListSection {
        @Setting("server-ping-sample")
        @Comment("Include online player sample in server list MOTD ping response; false = send empty sample list | Передавати список гравців у MOTD-пінгу; false = надсилати порожній список гравців")
        public boolean serverPingSample = true;

        @Setting("tab-latency-updates")
        @Comment("Run periodic O(N^2) player ping latency broadcasts in PlayerList; false = skip Tab latency packet loop | Виконувати періодичну O(N^2) розсилку пінгу гравців у Tab; false = вимкнути оновлення пінгу")
        public boolean tabLatencyUpdates = true;
    }

    @ConfigSerializable
    public static final class PacketRangeClampingSection {
        @Setting("full-packet-broadcast")
        @Comment("[MASTER SWITCH] true = standard vanilla broadcast ranges (caps below ignored); false = clamp projectile, particle & armor packets using the limits below | [ГОЛОВНИЙ РУБИЛЬНИК] true = ванільні дистанції пакетів (ліміти нижче ігноруються); false = урізати радіуси за лімітами нижче")
        public boolean fullPacketBroadcast = true;

        @Setting("projectile-tracking-range")
        @Comment("Max block range for Projectile tracking when full-packet-broadcast=false ([0..256]) | Макс. дистанція трекінгу снарядів при full-packet-broadcast=false ([0..256])")
        public int projectileTrackingRange = 32;

        @Setting("particle-broadcast-range")
        @Comment("Max block range for particle packets when full-packet-broadcast=false ([0.0..256.0]) | Макс. дистанція розсилки пакетів партиклів при full-packet-broadcast=false ([0.0..256.0])")
        public double particleBroadcastRange = 16.0d;

        @Setting("armor-equipment-range")
        @Comment("Max block range for LivingEntity equipment updates when full-packet-broadcast=false ([0..256]) | Макс. дистанція пакетів оновлення броні/спорядження при full-packet-broadcast=false ([0..256])")
        public int armorEquipmentRange = 32;
    }

    @ConfigSerializable
    public static final class StorageSection {
        @Setting("world-saving")
        @Comment("Persist chunks, region files, level.dat, SavedData & advancements to disk; false = ephemeral RAM-only worlds with 0 disk writes (also disables players.data-saving) | Зберігати чанки, регіони, level.dat і дані на диск; false = ефемерний режим лише в RAM (0 I/O, також вимикає players.data-saving)")
        public boolean worldSaving = true;

        @Setting("periodic-autosave")
        @Comment("Run periodic background chunk & player autosaves during ticks when storage.world-saving=true; false = save only on unload, /save-all or graceful shutdown | Періодичне фонове автозбереження чанків і гравців під час тіків; false = зберігати лише при вивантаженні/стопі")
        public boolean periodicAutosave = true;
    }

    @ConfigSerializable
    public static final class SystemSection {
        @Setting("lifecycle")
        @Comment("JVM server process lifecycle controls | Керування життєвим циклом процесу сервера")
        public SystemLifecycleSection lifecycle = new SystemLifecycleSection();
    }

    @ConfigSerializable
    public static final class SystemLifecycleSection {
        @Setting("graceful-shutdown")
        @Comment("Wait for async tasks, player saves & full chunk flush on stop; false = fast container shutdown without waiting on world/player flush | Плавна зупинка з очікуванням асинхронних задач і запису чанків; false = швидке вимкнення для контейнерів")
        public boolean gracefulShutdown = true;
    }

    @ConfigSerializable
    public static final class CompatibilitySection {
        @Setting("legacy")
        @Comment("Granular kill-switches for legacy Bukkit/Mojang subsystems | Окремі рубильники застарілих підсистем сумісності")
        public LegacySection legacy = new LegacySection();
    }

    @ConfigSerializable
    public static final class LegacySection {
        @Setting("plugin-bytecode-rerouting")
        @Comment("Run ASM Commodore bytecode rerouting for old Bukkit plugins; false = skip ASM plugin class transformation (only for modern 1.20.5+ plugins) | Застосовувати ASM-переписування байткоду плагінів (Commodore); false = без трансформації для сучасних плагінів")
        public boolean pluginBytecodeRerouting = true;

        @Setting("bootstrap-data-tables")
        @Comment("Build pre-1.13 block/item conversion tables in Bootstrap; false = skip allocating legacy CraftBukkit lookup maps | Будувати таблиці конвертації блоків/предметів до 1.13 у Bootstrap; false = пропустити на старті")
        public boolean bootstrapDataTables = true;

        @Setting("validate-entity-attributes")
        @Comment("Eagerly load all mob classes & validate DefaultAttributes in Bootstrap; false = resolve entity attributes lazily per type | Завантажувати класи всіх мобів і валідувати DefaultAttributes на старті; false = лінива ініціалізація")
        public boolean validateEntityAttributes = true;
    }

    @ConfigSerializable
    public static final class MessagesSection {
        @Setting("commands")
        @Comment("MiniMessage output templates for core world commands (/setworldspawn, /tpworld, /world) | Шаблони відповідей MiniMessage для команд світів")
        public CommandMessagesSection commands = new CommandMessagesSection();
    }

    @ConfigSerializable
    public static final class CommandMessagesSection {
        public static final String DEFAULT_NO_PERMISSION = "<red>You do not have permission to execute this command.";
        public static final String DEFAULT_PLAYER_ONLY = "<red>Only players can run this command without explicit world/coordinates.";
        public static final String DEFAULT_INVALID_SYNTAX_SETWORLDSPAWN = "<red>Usage: /setworldspawn [<x> <y> <z>] [<yaw> <pitch>]";
        public static final String DEFAULT_INVALID_SYNTAX_TPWORLD = "<red>Usage: /tpworld <world> [<player>] [<x> <y> <z>] [<yaw> <pitch>]";
        public static final String DEFAULT_INVALID_SYNTAX_WORLD = "<red>Usage: /world <load <world>|unload <world>|reload [world]>";
        public static final String DEFAULT_INVALID_NUMBER = "<red>Invalid numeric argument '<input>'.";
        public static final String DEFAULT_WORLD_NOT_FOUND = "<red>World '<world>' is not loaded or does not exist.";
        public static final String DEFAULT_PLAYER_NOT_FOUND = "<red>Player '<player>' is not online.";
        public static final String DEFAULT_SETWORLDSPAWN_SUCCESS = "<green>Spawn of world <white><world></white> updated to <gray>(<x>, <y>, <z> | yaw <yaw>, pitch <pitch>)</gray>.";
        public static final String DEFAULT_TPWORLD_SUCCESS_SELF = "<green>Teleported to <white><world></white> at <gray>(<x>, <y>, <z>)</gray>.";
        public static final String DEFAULT_TPWORLD_SUCCESS_OTHER = "<green>Teleported <white><player></white> to <white><world></white> at <gray>(<x>, <y>, <z>)</gray>.";
        public static final String DEFAULT_TPWORLD_FAILED = "<red>Failed to teleport to world '<world>'.";
        public static final String DEFAULT_WORLD_STANDALONE_ONLY = "<red>The /world command is only available when dimensions.mode is 'standalone'.";
        public static final String DEFAULT_WORLD_ALREADY_LOADED = "<yellow>World '<world>' is already loaded.";
        public static final String DEFAULT_WORLD_NOT_FOUND_ON_DISK = "<red>World folder '<world>' was not found in worlds/ or has an invalid name.";
        public static final String DEFAULT_WORLD_LOAD_SUCCESS = "<green>Loaded world <white><world></white>.";
        public static final String DEFAULT_WORLD_LOAD_FAILED = "<red>Failed to load world '<world>'.";
        public static final String DEFAULT_WORLD_UNLOAD_FALLBACK_FORBIDDEN = "<red>Cannot unload the base fallback world '<world>'.";
        public static final String DEFAULT_WORLD_FALLBACK_NOT_CONFIGURED = "<red>Base fallback world is not configured or not loaded; cannot evacuate players.";
        public static final String DEFAULT_WORLD_UNLOAD_SUCCESS = "<green>Unloaded world <white><world></white> (moved <gray><players></gray> player(s) to <white><fallback></white>).";
        public static final String DEFAULT_WORLD_UNLOAD_FAILED = "<red>Failed to unload world '<world>'.";
        public static final String DEFAULT_WORLD_RELOAD_SINGLE_SUCCESS = "<green>Reloaded world <white><world></white> from disk (returned <gray><players></gray> player(s)).";
        public static final String DEFAULT_WORLD_RELOAD_FALLBACK_NOTICE = "<yellow>Reloaded configuration for base world <white><world></white>. Full map reload is not supported for the base world.";
        public static final String DEFAULT_WORLD_RELOAD_MISSING_UNLOADED = "<yellow>Folder for '<world>' no longer exists on disk; world unloaded.";
        public static final String DEFAULT_WORLD_RELOAD_FAILED = "<red>Failed to reload world '<world>'.";
        public static final String DEFAULT_WORLD_RELOAD_ALL_SUCCESS = "<green>Reloaded worlds: <white><reloaded></white> reloaded, <white><loaded></white> loaded, <white><unloaded></white> unloaded; base world '<fallback>' config refreshed (full base world reload is not supported).";

        @Setting("no-permission")
        @Comment("Sent when sender lacks permission ('' mutes) | Немає прав ('' вимикає повідомлення)")
        public @Nullable String noPermission = DEFAULT_NO_PERMISSION;

        @Setting("player-only")
        @Comment("Sent when console omits required player context | Лише для гравців без явних аргументів")
        public @Nullable String playerOnly = DEFAULT_PLAYER_ONLY;

        @Setting("invalid-syntax-setworldspawn")
        @Comment("Sent when /setworldspawn argument count is invalid | Помилка синтаксису /setworldspawn")
        public @Nullable String invalidSyntaxSetWorldSpawn = DEFAULT_INVALID_SYNTAX_SETWORLDSPAWN;

        @Setting("invalid-syntax-tpworld")
        @Comment("Sent when /tpworld argument count is invalid | Помилка синтаксису /tpworld")
        public @Nullable String invalidSyntaxTpWorld = DEFAULT_INVALID_SYNTAX_TPWORLD;

        @Setting("invalid-syntax-world")
        @Comment("Sent when /world subcommand or arguments are invalid | Помилка синтаксису /world")
        public @Nullable String invalidSyntaxWorld = DEFAULT_INVALID_SYNTAX_WORLD;

        @Setting("invalid-number")
        @Comment("Sent when coordinate or rotation is not a valid finite number (placeholders: <input>) | Некоректне число (<input>)")
        public @Nullable String invalidNumber = DEFAULT_INVALID_NUMBER;

        @Setting("world-not-found")
        @Comment("Sent when target world is missing or unloaded (placeholders: <world>) | Світ не знайдено (<world>)")
        public @Nullable String worldNotFound = DEFAULT_WORLD_NOT_FOUND;

        @Setting("player-not-found")
        @Comment("Sent when target player is offline or unknown (placeholders: <player>) | Гравця не знайдено (<player>)")
        public @Nullable String playerNotFound = DEFAULT_PLAYER_NOT_FOUND;

        @Setting("setworldspawn-success")
        @Comment("Sent after /setworldspawn succeeds (<world>, <x>, <y>, <z>, <yaw>, <pitch>) | Успішне оновлення спавна світу")
        public @Nullable String setWorldSpawnSuccess = DEFAULT_SETWORLDSPAWN_SUCCESS;

        @Setting("tpworld-success-self")
        @Comment("Sent after self-teleport via /tpworld (<world>, <x>, <y>, <z>, <yaw>, <pitch>) | Успішна телепортація гравця")
        public @Nullable String tpWorldSuccessSelf = DEFAULT_TPWORLD_SUCCESS_SELF;

        @Setting("tpworld-success-other")
        @Comment("Sent after teleporting another player (<player>, <world>, <x>, <y>, <z>, <yaw>, <pitch>) | Успішна телепортація іншого гравця")
        public @Nullable String tpWorldSuccessOther = DEFAULT_TPWORLD_SUCCESS_OTHER;

        @Setting("tpworld-failed")
        @Comment("Sent if async teleport fails (<world>, <player>) | Помилка телепортації")
        public @Nullable String tpWorldFailed = DEFAULT_TPWORLD_FAILED;

        @Setting("world-standalone-only")
        @Comment("Sent when /world is run outside dimensions.mode=standalone | /world доступний лише у standalone-режимі")
        public @Nullable String worldStandaloneOnly = DEFAULT_WORLD_STANDALONE_ONLY;

        @Setting("world-already-loaded")
        @Comment("Sent when /world load targets an already loaded world (<world>) | Світ уже завантажено (<world>)")
        public @Nullable String worldAlreadyLoaded = DEFAULT_WORLD_ALREADY_LOADED;

        @Setting("world-not-found-on-disk")
        @Comment("Sent when /world load cannot find a valid world folder in worlds/ (<world>) | Теку світу не знайдено в worlds/ (<world>)")
        public @Nullable String worldNotFoundOnDisk = DEFAULT_WORLD_NOT_FOUND_ON_DISK;

        @Setting("world-load-success")
        @Comment("Sent after /world load succeeds (<world>) | Успішне завантаження світу (<world>)")
        public @Nullable String worldLoadSuccess = DEFAULT_WORLD_LOAD_SUCCESS;

        @Setting("world-load-failed")
        @Comment("Sent if loading a world fails (<world>) | Помилка завантаження світу (<world>)")
        public @Nullable String worldLoadFailed = DEFAULT_WORLD_LOAD_FAILED;

        @Setting("world-unload-fallback-forbidden")
        @Comment("Sent when attempting to unload the base fallback world (<world>) | Заборонено вивантажувати базовий світ (<world>)")
        public @Nullable String worldUnloadFallbackForbidden = DEFAULT_WORLD_UNLOAD_FALLBACK_FORBIDDEN;

        @Setting("world-fallback-not-configured")
        @Comment("Sent when base fallback-world is not assigned or not loaded for player evacuation | Базовий світ не налаштовано або не завантажено")
        public @Nullable String worldFallbackNotConfigured = DEFAULT_WORLD_FALLBACK_NOT_CONFIGURED;

        @Setting("world-unload-success")
        @Comment("Sent after /world unload succeeds (<world>, <players>, <fallback>) | Успішне вивантаження світу")
        public @Nullable String worldUnloadSuccess = DEFAULT_WORLD_UNLOAD_SUCCESS;

        @Setting("world-unload-failed")
        @Comment("Sent if unloading a world fails (<world>) | Помилка вивантаження світу (<world>)")
        public @Nullable String worldUnloadFailed = DEFAULT_WORLD_UNLOAD_FAILED;

        @Setting("world-reload-single-success")
        @Comment("Sent after reloading a non-base world from disk (<world>, <players>) | Успішне перезавантаження світу з диска")
        public @Nullable String worldReloadSingleSuccess = DEFAULT_WORLD_RELOAD_SINGLE_SUCCESS;

        @Setting("world-reload-fallback-notice")
        @Comment("Sent when /world reload <base_world> refreshes only world.yml (<world>) | Для базового світу оновлено лише конфігурацію")
        public @Nullable String worldReloadFallbackNotice = DEFAULT_WORLD_RELOAD_FALLBACK_NOTICE;

        @Setting("world-reload-missing-unloaded")
        @Comment("Sent when a reloaded world's folder no longer exists on disk (<world>) | Теку світу видалено на диску, світ вивантажено")
        public @Nullable String worldReloadMissingUnloaded = DEFAULT_WORLD_RELOAD_MISSING_UNLOADED;

        @Setting("world-reload-failed")
        @Comment("Sent if reloading a world fails (<world>) | Помилка перезавантаження світу (<world>)")
        public @Nullable String worldReloadFailed = DEFAULT_WORLD_RELOAD_FAILED;

        @Setting("world-reload-all-success")
        @Comment("Sent after /world reload refreshes all worlds (<reloaded>, <loaded>, <unloaded>, <fallback>) | Підсумок перезавантаження всіх світів")
        public @Nullable String worldReloadAllSuccess = DEFAULT_WORLD_RELOAD_ALL_SUCCESS;

        void fillMissingDefaults() {
            if (this.noPermission == null) this.noPermission = DEFAULT_NO_PERMISSION;
            if (this.playerOnly == null) this.playerOnly = DEFAULT_PLAYER_ONLY;
            if (this.invalidSyntaxSetWorldSpawn == null) this.invalidSyntaxSetWorldSpawn = DEFAULT_INVALID_SYNTAX_SETWORLDSPAWN;
            if (this.invalidSyntaxTpWorld == null) this.invalidSyntaxTpWorld = DEFAULT_INVALID_SYNTAX_TPWORLD;
            if (this.invalidSyntaxWorld == null) this.invalidSyntaxWorld = DEFAULT_INVALID_SYNTAX_WORLD;
            if (this.invalidNumber == null) this.invalidNumber = DEFAULT_INVALID_NUMBER;
            if (this.worldNotFound == null) this.worldNotFound = DEFAULT_WORLD_NOT_FOUND;
            if (this.playerNotFound == null) this.playerNotFound = DEFAULT_PLAYER_NOT_FOUND;
            if (this.setWorldSpawnSuccess == null) this.setWorldSpawnSuccess = DEFAULT_SETWORLDSPAWN_SUCCESS;
            if (this.tpWorldSuccessSelf == null) this.tpWorldSuccessSelf = DEFAULT_TPWORLD_SUCCESS_SELF;
            if (this.tpWorldSuccessOther == null) this.tpWorldSuccessOther = DEFAULT_TPWORLD_SUCCESS_OTHER;
            if (this.tpWorldFailed == null) this.tpWorldFailed = DEFAULT_TPWORLD_FAILED;
            if (this.worldStandaloneOnly == null) this.worldStandaloneOnly = DEFAULT_WORLD_STANDALONE_ONLY;
            if (this.worldAlreadyLoaded == null) this.worldAlreadyLoaded = DEFAULT_WORLD_ALREADY_LOADED;
            if (this.worldNotFoundOnDisk == null) this.worldNotFoundOnDisk = DEFAULT_WORLD_NOT_FOUND_ON_DISK;
            if (this.worldLoadSuccess == null) this.worldLoadSuccess = DEFAULT_WORLD_LOAD_SUCCESS;
            if (this.worldLoadFailed == null) this.worldLoadFailed = DEFAULT_WORLD_LOAD_FAILED;
            if (this.worldUnloadFallbackForbidden == null) {
                this.worldUnloadFallbackForbidden = DEFAULT_WORLD_UNLOAD_FALLBACK_FORBIDDEN;
            }
            if (this.worldFallbackNotConfigured == null) {
                this.worldFallbackNotConfigured = DEFAULT_WORLD_FALLBACK_NOT_CONFIGURED;
            }
            if (this.worldUnloadSuccess == null) this.worldUnloadSuccess = DEFAULT_WORLD_UNLOAD_SUCCESS;
            if (this.worldUnloadFailed == null) this.worldUnloadFailed = DEFAULT_WORLD_UNLOAD_FAILED;
            if (this.worldReloadSingleSuccess == null) this.worldReloadSingleSuccess = DEFAULT_WORLD_RELOAD_SINGLE_SUCCESS;
            if (this.worldReloadFallbackNotice == null) this.worldReloadFallbackNotice = DEFAULT_WORLD_RELOAD_FALLBACK_NOTICE;
            if (this.worldReloadMissingUnloaded == null) this.worldReloadMissingUnloaded = DEFAULT_WORLD_RELOAD_MISSING_UNLOADED;
            if (this.worldReloadFailed == null) this.worldReloadFailed = DEFAULT_WORLD_RELOAD_FAILED;
            if (this.worldReloadAllSuccess == null) this.worldReloadAllSuccess = DEFAULT_WORLD_RELOAD_ALL_SUCCESS;
        }
    }
}
