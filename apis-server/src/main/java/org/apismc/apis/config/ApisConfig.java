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
import java.util.List;
import org.apismc.apis.feature.FeatureRegistry;
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
 * activates the O(1) kill-switch and cuts out the subsystem completely.
 */
@ConfigSerializable
public final class ApisConfig {

    public static final String DEFAULT_FILE_NAME = "apis.yml";
    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");

    private static final String HEADER = """
        Apis Feature Matrix | Позитивна матриця модулів Apis
        true = standard Vanilla/Paper behavior enabled | true = стандартна механіка увімкнена
        false = subsystem killed for maximum performance & zero overhead | false = підсистема вирізана для оптимізації
        """;

    private static volatile ApisConfig instance = new ApisConfig();
    private static volatile Path configPath = Path.of(ApisPaths.CONFIG_DIR, DEFAULT_FILE_NAME);

    @Setting("startup")
    @Comment("Bootstrap & cold-start subsystems (true = active, false = skip at boot) | Підсистеми холодного старту (true = увімкнено, false = пропустити)")
    public StartupSection startup = new StartupSection();

    @Setting("worlds")
    @Comment("Worlds & dimensions loading/ticking switches (true = active, false = cut) | Світи, виміри та портали (true = увімкнено, false = вирізано)")
    public WorldsSection worlds = new WorldsSection();

    @Setting("entities")
    @Comment("Entity & AI capability matrix (true = active, false = cut) | Сутності та штучний інтелект (true = увімкнено, false = вирізано)")
    public EntitiesSection entities = new EntitiesSection();

    @Setting("chunks")
    @Comment("Chunk generation & ticking capabilities (true = active, false = cut) | Генерація та тіки чанків (true = увімкнено, false = вирізано)")
    public ChunksSection chunks = new ChunksSection();

    @Setting("gameplay")
    @Comment("Vanilla gameplay mechanics (true = active, false = cut) | Ігрові механіки ванілли (true = увімкнено, false = вирізано)")
    public GameplaySection gameplay = new GameplaySection();

    @Setting("network")
    @Comment("Network, crypto, tracking & telemetry switches (true = active, false = cut) | Мережа, шифрування, трекінг і метрики (true = увімкнено, false = вирізано)")
    public NetworkSection network = new NetworkSection();

    @Setting("storage")
    @Comment("Container disk storage & shutdown lifecycle (true = standard, false = ephemeral) | Сховище світу та вимкнення (true = стандартно, false = ефемерний режим)")
    public StorageSection storage = new StorageSection();

    @Setting("players")
    @Comment("Player persistence, hunger & statistics (true = active, false = cut) | Профілі, голод і статистика гравців (true = увімкнено, false = вирізано)")
    public PlayersSection players = new PlayersSection();

    @Setting("world")
    @Comment("World time & block/item physics (true = active, false = cut) | Час світу та фізика блоків/предметів (true = увімкнено, false = вирізано)")
    public WorldSection world = new WorldSection();

    @Setting("compatibility")
    @Comment("Legacy Bukkit/Minecraft compatibility layers (true = standard, false = disabled for speed/RAM) | Застарілі механізми сумісності (true = стандарт, false = вимкнено для швидкості і RAM)")
    public CompatibilitySection compatibility = new CompatibilitySection();

    public static ApisConfig get() {
        return instance;
    }

    public static Path configPath() {
        return configPath;
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
        LOGGER.info(
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
            final Path parent = path.normalize().getParent();
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
            final @Nullable ApisConfig cfg = root.get(ApisConfig.class);
            final ApisConfig effective = cfg != null ? cfg : new ApisConfig();
            root.set(ApisConfig.class, effective);
            saveWithComments(path, loader, root);
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
            logger.warn("Could not back up malformed config {}", path, ex);
        }
    }

    void sanitize(final Logger logger) {
        if (this.startup == null) this.startup = new StartupSection();
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
        if (this.compatibility == null) this.compatibility = new CompatibilitySection();
        if (this.compatibility.legacy == null) this.compatibility.legacy = new LegacySection();

        this.network.tracking.projectileTrackingRange = clampInt(
            this.network.tracking.projectileTrackingRange, 0, 256, "network.tracking.projectile-tracking-range", logger
        );
        this.network.tracking.armorEquipmentRange = clampInt(
            this.network.tracking.armorEquipmentRange, 0, 256, "network.tracking.armor-equipment-range", logger
        );
        if (Double.isNaN(this.network.tracking.particleBroadcastRange)
            || this.network.tracking.particleBroadcastRange < 0.0d
            || this.network.tracking.particleBroadcastRange > 256.0d) {
            final double clamped = Double.isNaN(this.network.tracking.particleBroadcastRange)
                ? 16.0d
                : Math.max(0.0d, Math.min(256.0d, this.network.tracking.particleBroadcastRange));
            logger.warn(
                "Clamping network.tracking.particle-broadcast-range={} to [0.0, 256.0] -> {}",
                this.network.tracking.particleBroadcastRange,
                clamped
            );
            this.network.tracking.particleBroadcastRange = clamped;
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
        @Comment("true = build DFU/DataConverter legacy migration schemas; false = skip DFU/MCTypeRegistry init (~240ms+ boot gain) | true = конвертери DFU, false = пропустити на старті")
        public boolean datafixers = true;

        @Setting("search-spawn-point")
        @Comment("true = scan 11x11 chunks for safe spawn; false = instant deterministic spawn at (0, seaLevel, 0) | true = пошук спавна 11x11, false = миттєво (0, seaLevel, 0)")
        public boolean searchSpawnPoint = true;

        @Setting("initial-chunk-load")
        @Comment("true = preload and wait for spawn chunks before Done; false = skip initial chunk wait loop | true = чекати стартові чанки, false = не блокувати запуск")
        public boolean initialChunkLoad = true;

        @Setting("validate-registries")
        @Comment("true = validate creative tabs & loot tables at boot; false = skip validation checks | true = валідувати вкладки і лут, false = пропустити валідацію")
        public boolean validateRegistries = true;

        @Setting("mojang-service-discovery")
        @Comment("true = query discovery.minecraftservices.com at boot; false = use offline stub without HTTP/DNS wait (~1.5-2s gain offline) | true = запитувати сервіси Mojang, false = офлайн-заглушка без мережевої затримки")
        public boolean mojangServiceDiscovery = true;

        @Setting("paper-version-check")
        @Comment("true = query fill.papermc.io / api.github.com for Paper build updates; false = disable startup version check | true = перевіряти оновлення Paper на старті, false = вимкнути мережеву перевірку версії")
        public boolean paperVersionCheck = true;

        @Setting("eager-blockstate-cache")
        @Comment("true = precompute collision, occlusion & shape caches for all 27k+ BlockStates at boot; false = lazy on-demand per state | true = прогрівати кеш усіх станів блоків на старті, false = ледачий розрахунок по запиту")
        public boolean eagerBlockstateCache = true;

        @Setting("eager-craftblockdata-cache")
        @Comment("true = pre-populate CraftBlockData ENCODER_CACHE & clones on class init; false = lazy on-demand parsing & caching | true = заповнювати кеш CraftBlockData на старті, false = ледаче кешування")
        public boolean eagerCraftblockdataCache = true;
    }

    @ConfigSerializable
    public static final class WorldsSection {
        @Setting("disabled-dimensions")
        @Comment("Dimensions skipped before ServerLevel allocation (e.g. minecraft:the_nether, minecraft:the_end) | Виміри, що не завантажуються і не алокуються в пам'яті")
        public List<String> disabledDimensions = new ArrayList<>();

        @Setting("custom-worlds")
        @Comment("true = allow plugins to create/load extra worlds; false = block runtime world creation | true = дозволити додаткові світи, false = заблокувати створення")
        public boolean customWorlds = true;

        @Setting("portals")
        @Comment("true = allow Nether/End portal transitions; false = block all portal travel | true = портали працюють, false = переходи вимкнено")
        public boolean portals = true;

        @Setting("disabled-ticking-dimensions")
        @Comment("Dimensions that load without ticking entities or chunks | Виміри, що завантажуються без тіків сутностей і чанків")
        public List<String> disabledTickingDimensions = new ArrayList<>();
    }

    @ConfigSerializable
    public static final class EntitiesSection {
        @Setting("ai")
        @Comment("Entity AI, brains, goals & collision flags | Прапори ШІ, мозку, цілей та колізій сутностей")
        public AiSection ai = new AiSection();
    }

    @ConfigSerializable
    public static final class AiSection {
        @Setting("enabled")
        @Comment("true = enable mob AI execution globally; false = strip AI & goal registration from all mobs | true = ШІ працює, false = вимкнути ШІ у всіх мобів")
        public boolean enabled = true;

        @Setting("disabled-types")
        @Comment("Entity types with AI disabled (e.g. minecraft:villager) | Типи сутностей, для яких вимкнено ШІ")
        public List<String> disabledTypes = new ArrayList<>();

        @Setting("goal-selector")
        @Comment("true = tick GoalSelector & targetSelector; false = skip goal execution | true = тікати GoalSelector, false = пропустити цілі")
        public boolean goalSelector = true;

        @Setting("brain")
        @Comment("true = initialize & tick mob Brain; false = skip Brain logic | true = мозок Brain активний, false = вимкнути Brain")
        public boolean brain = true;

        @Setting("pathfinding")
        @Comment("true = run PathNavigation path recalculation; false = skip navigation ticks | true = пошук шляхів увімкнено, false = вимкнути навігацію")
        public boolean pathfinding = true;

        @Setting("sensors")
        @Comment("true = tick mob Sensing & brain sensors; false = skip sensor updates | true = сенсори активні, false = вимкнути сенсори")
        public boolean sensors = true;

        @Setting("collisions")
        @Comment("true = enable entity-to-entity collisions; false = disable all push collisions | true = фізичні колізії увімкнено, false = вимкнути зіштовхування")
        public boolean collisions = true;
    }

    @ConfigSerializable
    public static final class ChunksSection {
        @Setting("structures")
        @Comment("true = generate world structures; false = skip structure placement & search | true = генерація структур, false = вирізати структури")
        public boolean structures = true;

        @Setting("biome-decoration")
        @Comment("true = generate ores, trees & biome features; false = skip decoration | true = декорація біомів, false = пропустити генерацію руд і дерев")
        public boolean biomeDecoration = true;

        @Setting("chunk-ticking")
        @Comment("true = tick loaded chunks in ServerChunkCache; false = skip chunk ticking loop | true = тікінг чанків, false = зупинити цикл тіків чанків")
        public boolean chunkTicking = true;

        @Setting("random-ticks")
        @Comment("true = run random block ticks (crops, grass, ice); false = freeze random ticks | true = випадкові тіки блоків, false = вимкнути")
        public boolean randomTicks = true;

        @Setting("block-ticks")
        @Comment("true = run scheduled block ticks; false = skip scheduled block ticks | true = заплановані тіки блоків, false = вимкнути")
        public boolean blockTicks = true;

        @Setting("fluid-ticks")
        @Comment("true = run scheduled water/lava fluid ticks; false = freeze fluid updates | true = тіки рідин (вода/лава), false = зупинити рідини")
        public boolean fluidTicks = true;
    }

    @ConfigSerializable
    public static final class GameplaySection {
        @Setting("weather")
        @Comment("true = allow rain/thunder cycles; false = force clear weather | true = погода змінюється, false = завжди ясно")
        public boolean weather = true;

        @Setting("raids")
        @Comment("true = allow village raids; false = disable raid creation & ticking | true = рейди активні, false = вимкнути рейди")
        public boolean raids = true;

        @Setting("redstone")
        @Comment("true = evaluate redstone wire & neighbor updates; false = kill redstone logic | true = редстоун працює, false = вимкнути редстоун")
        public boolean redstone = true;

        @Setting("natural-spawning")
        @Comment("true = allow natural mob spawning in chunks; false = stop natural spawning | true = природний спавн мобів, false = вимкнути спавн")
        public boolean naturalSpawning = true;

        @Setting("custom-spawners")
        @Comment("true = enable phantom, patrol, cat, siege & trader spawners; false = skip them | true = фантоми/патрулі/торговці, false = вирізати спавнери")
        public boolean customSpawners = true;

        @Setting("monsters")
        @Comment("true = allow natural monster spawning; false = block MobCategory.MONSTER spawns | true = природний спавн монстрів, false = заборонити")
        public boolean monsters = true;

        @Setting("animals")
        @Comment("true = allow natural animal & ambient spawning; false = block friendly category spawns | true = природний спавн тварин, false = заборонити")
        public boolean animals = true;

        @Setting("advancements")
        @Comment("true = compile & track advancements; false = skip ServerAdvancementManager tree & player tracking | true = досягнення, false = вирізати повністю")
        public boolean advancements = true;

        @Setting("recipes")
        @Comment("true = compile & sync server recipe book; false = skip RecipeManager compilation & sync | true = рецепти, false = вирізати компіляцію і синхронізацію")
        public boolean recipes = true;

        @Setting("loot-tables")
        @Comment("true = parse datapack loot_table JSON definitions; false = skip loot table loading (returns LootTable.EMPTY) | true = завантажувати таблиці луту, false = пропустити на старті")
        public boolean lootTables = true;

        @Setting("warden-and-sleep")
        @Comment("true = tick warden spawn tracker, sleep night-skip & dragon fight; false = skip all three | true = варден/сон/дракон, false = вимкнути")
        public boolean wardenAndSleep = true;

        @Setting("command-functions")
        @Comment("true = tick datapack .mcfunction schedules; false = skip ServerFunctionManager#tick | true = тіки .mcfunction, false = вимкнути функції датапаків")
        public boolean commandFunctions = true;
    }

    @ConfigSerializable
    public static final class NetworkSection {
        @Setting("generate-keypair")
        @Comment("true = generate RSA keypair on boot; false = skip in offline/proxy mode (RustyConnector/Velocity) | true = RSA-ключі на старті, false = пропустити в offline")
        public boolean generateKeypair = true;

        @Setting("analytics")
        @Comment("Telemetry, profiler & debug sync flags | Метрики, профайлер та дебаг-синхронізація")
        public AnalyticsSection analytics = new AnalyticsSection();

        @Setting("tracking")
        @Comment("Player collisions, packet range filtering & ping flags | Колізії гравців, фільтрація пакетів і пінг")
        public TrackingSection tracking = new TrackingSection();
    }

    @ConfigSerializable
    public static final class AnalyticsSection {
        @Setting("metrics-and-profiler")
        @Comment("true = enable bStats metrics, Spark & activity monitor; false = cut telemetry | true = метрики та Spark, false = вимкнути телеметрію")
        public boolean metricsAndProfiler = true;

        @Setting("debug-synchronizers")
        @Comment("true = tick server debug synchronizers; false = skip debug sync ticks | true = дебаг-синхронізатори, false = вимкнути")
        public boolean debugSynchronizers = true;
    }

    @ConfigSerializable
    public static final class TrackingSection {
        @Setting("player-collisions")
        @Comment("true = players collide/push each other; false = disable player pushing (lobbies/PvP) | true = штовхання гравців, false = вимкнути колізії гравців")
        public boolean playerCollisions = true;

        @Setting("full-packet-broadcast")
        @Comment("true = vanilla tracking distances; false = clamp projectile/particle/armor ranges below | true = ванільна дальність пакетів, false = урізати радіуси")
        public boolean fullPacketBroadcast = true;

        @Setting("projectile-tracking-range")
        @Comment("Max block range for projectile tracking when full-packet-broadcast is false | Макс. дистанція трекінгу снарядів при full-packet-broadcast=false")
        public int projectileTrackingRange = 32;

        @Setting("particle-broadcast-range")
        @Comment("Max block range for particle packets when full-packet-broadcast is false | Макс. дистанція пакетів партиклів при full-packet-broadcast=false")
        public double particleBroadcastRange = 16.0d;

        @Setting("armor-equipment-range")
        @Comment("Max block range for equipment updates when full-packet-broadcast is false | Макс. дистанція пакетів броні при full-packet-broadcast=false")
        public int armorEquipmentRange = 32;

        @Setting("server-ping-sample")
        @Comment("true = include player list sample in MOTD ping; false = omit sample for proxies | true = список гравців у пінгу, false = сховати")
        public boolean serverPingSample = true;

        @Setting("tab-latency-updates")
        @Comment("true = run periodic O(N^2) PlayerList latency broadcast; false = skip tab ping sync | true = оновлення пінгу в Tab, false = вимкнути O(N^2) цикл")
        public boolean tabLatencyUpdates = true;
    }

    @ConfigSerializable
    public static final class StorageSection {
        @Setting("ephemeral")
        @Comment("Disk persistence & shutdown behavior for Docker arenas | Збереження світу на диск та вимкнення для Docker-арен")
        public EphemeralStorageSection ephemeral = new EphemeralStorageSection();
    }

    @ConfigSerializable
    public static final class EphemeralStorageSection {
        @Setting("world-saving")
        @Comment("true = persist chunks, regions & level.dat to disk; false = ephemeral memory-only world (0 I/O) | true = запис на диск, false = лише RAM (0 I/O)")
        public boolean worldSaving = true;

        @Setting("graceful-shutdown")
        @Comment("true = flush chunks & pools on stop; false = instant SIGTERM container shutdown | true = плавна зупинка з флашем, false = миттєвий SIGTERM стоп")
        public boolean gracefulShutdown = true;
    }

    @ConfigSerializable
    public static final class PlayersSection {
        @Setting("lifecycle")
        @Comment("Player save, hunger & statistics flags | Збереження гравців, голод і статистика")
        public PlayerLifecycleSection lifecycle = new PlayerLifecycleSection();
    }

    @ConfigSerializable
    public static final class PlayerLifecycleSection {
        @Setting("player-data-saving")
        @Comment("true = save playerdata .dat files; false = skip playerdata disk writes | true = зберігати .dat гравців, false = не писати playerdata")
        public boolean playerDataSaving = true;

        @Setting("hunger")
        @Comment("true = tick player hunger, exhaustion & regen; false = freeze hunger | true = голод і регенерація, false = вимкнути FoodData#tick")
        public boolean hunger = true;

        @Setting("statistics")
        @Comment("true = record player stats & per-tick criteria triggers; false = skip stats increments | true = статистика гравців, false = не рахувати")
        public boolean statistics = true;
    }

    @ConfigSerializable
    public static final class WorldSection {
        @Setting("daylight-cycle")
        @Comment("true = tick WorldClock time & broadcast SetTime packets; false = freeze time without network spam | true = цикл дня/ночі, false = заморозити час")
        public boolean daylightCycle = true;

        @Setting("physics")
        @Comment("Block & item physics capabilities | Фізика блоків, тайл-еніті та предметів")
        public PhysicsSection physics = new PhysicsSection();
    }

    @ConfigSerializable
    public static final class PhysicsSection {
        @Setting("block-gravity")
        @Comment("true = allow falling sand, gravel & anvils; false = disable block gravity | true = падіння піску/гравію/ковадл, false = вимкнути гравітацію")
        public boolean blockGravity = true;

        @Setting("hopper-ticking")
        @Comment("true = tick HopperBlockEntity transfers; false = freeze hoppers | true = тіки воронок, false = вимкнути воронки")
        public boolean hopperTicking = true;

        @Setting("block-entity-ticking")
        @Comment("true = tick furnaces, beacons, chests & all block-entities; false = skip block-entity ticking | true = тікінг усіх тайл-еніті, false = вимкнути")
        public boolean blockEntityTicking = true;

        @Setting("item-physics")
        @Comment("true = run movement & collision physics on ItemEntity; false = keep age/despawn without physics | true = фізика предметів, false = без колізій/руху")
        public boolean itemPhysics = true;

        @Setting("leaf-decay")
        @Comment("true = allow natural leaf decay ticks; false = keep leaves permanent | true = опадання листя, false = вимкнути")
        public boolean leafDecay = true;

        @Setting("fire-spread")
        @Comment("true = allow fire spread & burn ticks; false = disable fire spread | true = поширення вогню, false = вимкнути")
        public boolean fireSpread = true;
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
        @Comment("true = run ASM Commodore bytecode rerouting for old Bukkit plugins; false = load plugin bytecode directly | true = ASM-переписування плагінів Commodore, false = без трансформації байткоду")
        public boolean pluginBytecodeRerouting = true;

        @Setting("bootstrap-data-tables")
        @Comment("true = build pre-1.13 block/item conversion tables in Bootstrap; false = skip legacy conversion maps | true = таблиці конвертації до 1.13 у Bootstrap, false = пропустити")
        public boolean bootstrapDataTables = true;

        @Setting("eager-map-color-cache")
        @Comment("true = enable 16 MB CraftMapColorCache lookup table & background build; false = direct lazy color matching (0 MB) | true = таблиця кольорів карт 16 МБ, false = лінивий підбір без буфера")
        public boolean eagerMapColorCache = true;

        @Setting("validate-entity-attributes")
        @Comment("true = eagerly load all mob classes & validate attributes in Bootstrap; false = lazy attribute resolution | true = валідація атрибутів усіх мобів на старті, false = лінива ініціалізація")
        public boolean validateEntityAttributes = true;
    }
}
