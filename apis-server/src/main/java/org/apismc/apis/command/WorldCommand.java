package org.apismc.apis.command;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.server.MinecraftServer;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.world.StandaloneWorldManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * Dynamic standalone world management command ({@code /world <load|unload|reload>}).
 */
public final class WorldCommand extends Command {

    public static final String PERMISSION = "apis.command.world";
    private static final List<String> SUBCOMMANDS = List.of("load", "unload", "reload");

    private final MinecraftServer server;

    public WorldCommand(final MinecraftServer server) {
        super(
            "world",
            "Loads, unloads, and reloads standalone worlds dynamically",
            "/world <load <world>|unload <world>|reload [world]>",
            List.of()
        );
        this.server = server;
        this.setPermission(PERMISSION);
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        final ApisConfig.CommandMessagesSection msg = ApisMessageRenderer.commandMessages();
        if (!ApisMessageRenderer.hasAccess(sender, PERMISSION)) {
            ApisMessageRenderer.send(sender, msg.noPermission, ApisConfig.CommandMessagesSection.DEFAULT_NO_PERMISSION);
            return true;
        }
        if (!StandaloneWorldManager.isStandaloneMode()) {
            ApisMessageRenderer.send(
                sender,
                msg.worldStandaloneOnly,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_STANDALONE_ONLY
            );
            return true;
        }
        if (args.length == 0) {
            sendInvalidSyntax(sender, msg);
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "load" -> {
                if (args.length != 2) {
                    sendInvalidSyntax(sender, msg);
                    return true;
                }
                this.handleLoad(sender, msg, args[1]);
            }
            case "unload" -> {
                if (args.length != 2) {
                    sendInvalidSyntax(sender, msg);
                    return true;
                }
                this.handleUnload(sender, msg, args[1]);
            }
            case "reload" -> {
                if (args.length == 1) {
                    this.handleReloadAll(sender, msg);
                    return true;
                }
                if (args.length == 2) {
                    this.handleReloadSingle(sender, msg, args[1]);
                    return true;
                }
                sendInvalidSyntax(sender, msg);
            }
            default -> sendInvalidSyntax(sender, msg);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!ApisMessageRenderer.hasAccess(sender, PERMISSION) || !StandaloneWorldManager.isStandaloneMode()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            final String sub = args[0].toLowerCase(Locale.ROOT);
            return switch (sub) {
                case "load" -> filterByPrefix(StandaloneWorldManager.getUnloadedStandaloneWorldNames(this.server), args[1]);
                case "unload" -> filterByPrefix(
                    StandaloneWorldManager.getUnloadableStandaloneWorldNames(this.server),
                    args[1]
                );
                case "reload" -> filterByPrefix(StandaloneWorldManager.getLoadedStandaloneWorldNames(this.server), args[1]);
                default -> Collections.emptyList();
            };
        }
        return Collections.emptyList();
    }

    private void handleLoad(
        final CommandSender sender,
        final ApisConfig.CommandMessagesSection msg,
        final String worldName
    ) {
        final StandaloneWorldManager.LoadResult result = StandaloneWorldManager.loadStandaloneWorld(this.server, worldName);
        switch (result.status()) {
            case LOADED -> ApisMessageRenderer.send(
                sender,
                msg.worldLoadSuccess,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_LOAD_SUCCESS,
                Placeholder.unparsed("world", result.worldName())
            );
            case ALREADY_LOADED -> ApisMessageRenderer.send(
                sender,
                msg.worldAlreadyLoaded,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_ALREADY_LOADED,
                Placeholder.unparsed("world", result.worldName())
            );
            case NOT_FOUND_ON_DISK -> ApisMessageRenderer.send(
                sender,
                msg.worldNotFoundOnDisk,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_NOT_FOUND_ON_DISK,
                Placeholder.unparsed("world", result.worldName())
            );
            case FAILED -> ApisMessageRenderer.send(
                sender,
                msg.worldLoadFailed,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_LOAD_FAILED,
                Placeholder.unparsed("world", result.worldName())
            );
        }
    }

    private void handleUnload(
        final CommandSender sender,
        final ApisConfig.CommandMessagesSection msg,
        final String worldName
    ) {
        final StandaloneWorldManager.UnloadResult result =
            StandaloneWorldManager.unloadStandaloneWorld(this.server, worldName);
        switch (result.status()) {
            case UNLOADED -> ApisMessageRenderer.send(
                sender,
                msg.worldUnloadSuccess,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_UNLOAD_SUCCESS,
                Placeholder.unparsed("world", result.worldName()),
                Placeholder.unparsed("fallback", result.fallbackName()),
                Placeholder.unparsed("players", Integer.toString(result.movedPlayers()))
            );
            case CANNOT_UNLOAD_FALLBACK -> ApisMessageRenderer.send(
                sender,
                msg.worldUnloadFallbackForbidden,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_UNLOAD_FALLBACK_FORBIDDEN,
                Placeholder.unparsed("world", result.worldName())
            );
            case NO_FALLBACK_WORLD -> ApisMessageRenderer.send(
                sender,
                msg.worldFallbackNotConfigured,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_FALLBACK_NOT_CONFIGURED
            );
            case NOT_LOADED -> ApisMessageRenderer.send(
                sender,
                msg.worldNotFound,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_NOT_FOUND,
                Placeholder.unparsed("world", result.worldName())
            );
            case FAILED -> ApisMessageRenderer.send(
                sender,
                msg.worldUnloadFailed,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_UNLOAD_FAILED,
                Placeholder.unparsed("world", result.worldName())
            );
        }
    }

    private void handleReloadSingle(
        final CommandSender sender,
        final ApisConfig.CommandMessagesSection msg,
        final String worldName
    ) {
        final StandaloneWorldManager.ReloadSingleResult result =
            StandaloneWorldManager.reloadSingleStandaloneWorld(this.server, worldName);
        switch (result.status()) {
            case RELOADED -> ApisMessageRenderer.send(
                sender,
                msg.worldReloadSingleSuccess,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_RELOAD_SINGLE_SUCCESS,
                Placeholder.unparsed("world", result.worldName()),
                Placeholder.unparsed("players", Integer.toString(result.returnedPlayers()))
            );
            case FALLBACK_CONFIG_ONLY -> ApisMessageRenderer.send(
                sender,
                msg.worldReloadFallbackNotice,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_RELOAD_FALLBACK_NOTICE,
                Placeholder.unparsed("world", result.worldName())
            );
            case MISSING_FOLDER_UNLOADED -> ApisMessageRenderer.send(
                sender,
                msg.worldReloadMissingUnloaded,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_RELOAD_MISSING_UNLOADED,
                Placeholder.unparsed("world", result.worldName())
            );
            case NOT_LOADED -> ApisMessageRenderer.send(
                sender,
                msg.worldNotFound,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_NOT_FOUND,
                Placeholder.unparsed("world", result.worldName())
            );
            case NO_FALLBACK_WORLD -> ApisMessageRenderer.send(
                sender,
                msg.worldFallbackNotConfigured,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_FALLBACK_NOT_CONFIGURED
            );
            case FAILED -> ApisMessageRenderer.send(
                sender,
                msg.worldReloadFailed,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_RELOAD_FAILED,
                Placeholder.unparsed("world", result.worldName())
            );
        }
    }

    private void handleReloadAll(final CommandSender sender, final ApisConfig.CommandMessagesSection msg) {
        final StandaloneWorldManager.ReloadAllResult result =
            StandaloneWorldManager.reloadAllStandaloneWorlds(this.server);
        if (result.status() == StandaloneWorldManager.ReloadAllStatus.NO_FALLBACK_WORLD) {
            ApisMessageRenderer.send(
                sender,
                msg.worldFallbackNotConfigured,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_FALLBACK_NOT_CONFIGURED
            );
            return;
        }
        ApisMessageRenderer.send(
            sender,
            msg.worldReloadAllSuccess,
            ApisConfig.CommandMessagesSection.DEFAULT_WORLD_RELOAD_ALL_SUCCESS,
            Placeholder.unparsed("reloaded", Integer.toString(result.reloaded())),
            Placeholder.unparsed("loaded", Integer.toString(result.loaded())),
            Placeholder.unparsed("unloaded", Integer.toString(result.unloaded())),
            Placeholder.unparsed("fallback", result.fallbackName())
        );
    }

    private static void sendInvalidSyntax(final CommandSender sender, final ApisConfig.CommandMessagesSection msg) {
        ApisMessageRenderer.send(
            sender,
            msg.invalidSyntaxWorld,
            ApisConfig.CommandMessagesSection.DEFAULT_INVALID_SYNTAX_WORLD
        );
    }

    private static List<String> filterByPrefix(final List<String> candidates, final String rawPrefix) {
        final String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
            .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
            .toList();
    }
}
