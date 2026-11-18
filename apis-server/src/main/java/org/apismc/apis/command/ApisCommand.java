package org.apismc.apis.command;

import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.config.ApisConfigSync;
import org.apismc.apis.feature.FeatureRegistry;
import org.apismc.apis.hooks.ApisHooks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic and runtime-reload command (/apis status|reload).
 */
public final class ApisCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("status", "reload");
    private final MinecraftServer server;

    private ApisCommand(final MinecraftServer server) {
        super("apis", "Apis diagnostics and runtime reload", "/apis <status|reload>", List.of("ap"));
        this.server = server;
        this.setPermission("apis.admin");
    }

    /**
     * Registers the /apis command into the server CommandMap during bootstrap.
     *
     * @param server active MinecraftServer instance
     */
    public static void register(final MinecraftServer server) {
        final PluginManager pluginManager = server.server.getPluginManager();
        registerOpPermission(
            pluginManager,
            SetWorldSpawnOverrideCommand.PERMISSION,
            "Allows setting standalone world spawn in world.yml"
        );
        registerOpPermission(pluginManager, TpWorldCommand.PERMISSION, "Allows teleporting between standalone worlds");

        final SimpleCommandMap commandMap = server.server.getCommandMap();
        commandMap.register("apis", new ApisCommand(server));
        commandMap.register("apis", new TpWorldCommand(server));
        if (ApisHooks.isStandaloneWorldsMode()) {
            final @Nullable Command vanillaSetSpawn = commandMap.getCommand("setworldspawn");
            if (vanillaSetSpawn != null && commandMap.getCommand("minecraft:setworldspawn") == null) {
                commandMap.getKnownCommands().put("minecraft:setworldspawn", vanillaSetSpawn);
            }
            commandMap.register("apis", new SetWorldSpawnOverrideCommand());
        }
    }

    private static void registerOpPermission(final PluginManager pm, final String name, final String description) {
        if (pm.getPermission(name) == null) {
            pm.addPermission(new Permission(name, description, PermissionDefault.OP));
        }
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            final List<String> active = FeatureRegistry.activeKillSwitches();
            sender.sendMessage(
                Component.text("Active kill-switches (" + active.size() + "): ", NamedTextColor.GOLD)
                    .append(Component.text(active.isEmpty() ? "none" : String.join(", ", active), NamedTextColor.GRAY))
            );
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            ApisConfig.reload();
            FeatureRegistry.resolveRegistries(this.server.registryAccess(), LoggerFactory.getLogger("Minecraft"));
            ApisConfigSync.syncAll(this.server);
            sender.sendMessage(
                Component.text(
                    "Reloaded apis.yml (" + FeatureRegistry.activeKillSwitches().size() + " active).",
                    NamedTextColor.GREEN
                )
            );
            return true;
        }
        sender.sendMessage(Component.text("Usage: /apis <status|reload>", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1 && sender.hasPermission("apis.admin")) {
            final String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }
}
