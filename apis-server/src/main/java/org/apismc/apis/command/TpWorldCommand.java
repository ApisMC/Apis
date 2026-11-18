package org.apismc.apis.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.server.MinecraftServer;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.world.StandaloneWorldManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/**
 * Cross-world administrative teleport command ({@code /tpworld}) respecting {@code world.yml} spawns,
 * exact coordinates, and player camera retention semantics.
 */
public final class TpWorldCommand extends Command {

    public static final String PERMISSION = "apis.command.tpworld";
    private final MinecraftServer server;

    public TpWorldCommand(final MinecraftServer server) {
        super(
            "tpworld",
            "Teleports yourself or another player to a loaded standalone world",
            "/tpworld <world> [<player>] [<x> <y> <z>] [<yaw> <pitch>]",
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
        final int len = args.length;
        if (len != 1 && len != 2 && len != 4 && len != 5 && len != 6 && len != 7) {
            ApisMessageRenderer.send(
                sender,
                msg.invalidSyntaxTpWorld,
                ApisConfig.CommandMessagesSection.DEFAULT_INVALID_SYNTAX_TPWORLD
            );
            return true;
        }

        final @Nullable World targetWorld = StandaloneWorldManager.resolveLoadedStandaloneWorld(this.server, args[0]);
        if (targetWorld == null) {
            ApisMessageRenderer.send(
                sender,
                msg.worldNotFound,
                ApisConfig.CommandMessagesSection.DEFAULT_WORLD_NOT_FOUND,
                Placeholder.unparsed("world", args[0])
            );
            return true;
        }

        final boolean hasTargetPlayerArg = len == 2 || len == 5 || len == 7;
        final Player targetPlayer;
        if (hasTargetPlayerArg) {
            final @Nullable Player resolved = resolvePlayer(args[1]);
            if (resolved == null || !resolved.isOnline()) {
                ApisMessageRenderer.send(
                    sender,
                    msg.playerNotFound,
                    ApisConfig.CommandMessagesSection.DEFAULT_PLAYER_NOT_FOUND,
                    Placeholder.unparsed("player", args[1])
                );
                return true;
            }
            targetPlayer = resolved;
        } else if (sender instanceof Player self) {
            targetPlayer = self;
        } else {
            ApisMessageRenderer.send(sender, msg.playerOnly, ApisConfig.CommandMessagesSection.DEFAULT_PLAYER_ONLY);
            return true;
        }

        final boolean isSelf = sender instanceof Player self && self.getUniqueId().equals(targetPlayer.getUniqueId());
        final Location destination;
        if (len == 1 || len == 2) {
            destination = StandaloneWorldManager.getSpawnLocation(targetWorld);
        } else {
            final int coordIndex = hasTargetPlayerArg ? 2 : 1;
            final @Nullable Double x = ApisMessageRenderer.parseFiniteDouble(args[coordIndex]);
            if (x == null) {
                ApisMessageRenderer.sendInvalidNumber(sender, msg, args[coordIndex]);
                return true;
            }
            final @Nullable Double y = ApisMessageRenderer.parseFiniteDouble(args[coordIndex + 1]);
            if (y == null) {
                ApisMessageRenderer.sendInvalidNumber(sender, msg, args[coordIndex + 1]);
                return true;
            }
            final @Nullable Double z = ApisMessageRenderer.parseFiniteDouble(args[coordIndex + 2]);
            if (z == null) {
                ApisMessageRenderer.sendInvalidNumber(sender, msg, args[coordIndex + 2]);
                return true;
            }

            final float yaw;
            final float pitch;
            if (len == 6 || len == 7) {
                final @Nullable Float parsedYaw = ApisMessageRenderer.parseFiniteFloat(args[coordIndex + 3]);
                if (parsedYaw == null) {
                    ApisMessageRenderer.sendInvalidNumber(sender, msg, args[coordIndex + 3]);
                    return true;
                }
                final @Nullable Float parsedPitch = ApisMessageRenderer.parseFiniteFloat(args[coordIndex + 4]);
                if (parsedPitch == null) {
                    ApisMessageRenderer.sendInvalidNumber(sender, msg, args[coordIndex + 4]);
                    return true;
                }
                yaw = parsedYaw;
                pitch = parsedPitch;
            } else if (isSelf) {
                yaw = targetPlayer.getLocation().getYaw();
                pitch = targetPlayer.getLocation().getPitch();
            } else {
                yaw = 0.0f;
                pitch = 0.0f;
            }
            destination = new Location(targetWorld, x, y, z, yaw, pitch);
        }

        targetPlayer.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.COMMAND)
            .whenComplete((success, error) -> {
                if (!Boolean.TRUE.equals(success) || error != null) {
                    ApisMessageRenderer.send(
                        sender,
                        msg.tpWorldFailed,
                        ApisConfig.CommandMessagesSection.DEFAULT_TPWORLD_FAILED,
                        Placeholder.unparsed("world", targetWorld.getName()),
                        Placeholder.unparsed("player", targetPlayer.getName())
                    );
                    return;
                }
                if (isSelf) {
                    ApisMessageRenderer.send(
                        sender,
                        msg.tpWorldSuccessSelf,
                        ApisConfig.CommandMessagesSection.DEFAULT_TPWORLD_SUCCESS_SELF,
                        Placeholder.unparsed("world", targetWorld.getName()),
                        ApisMessageRenderer.numberTag("x", destination.getX()),
                        ApisMessageRenderer.numberTag("y", destination.getY()),
                        ApisMessageRenderer.numberTag("z", destination.getZ()),
                        ApisMessageRenderer.numberTag("yaw", destination.getYaw()),
                        ApisMessageRenderer.numberTag("pitch", destination.getPitch())
                    );
                } else {
                    ApisMessageRenderer.send(
                        sender,
                        msg.tpWorldSuccessOther,
                        ApisConfig.CommandMessagesSection.DEFAULT_TPWORLD_SUCCESS_OTHER,
                        Placeholder.unparsed("player", targetPlayer.getName()),
                        Placeholder.unparsed("world", targetWorld.getName()),
                        ApisMessageRenderer.numberTag("x", destination.getX()),
                        ApisMessageRenderer.numberTag("y", destination.getY()),
                        ApisMessageRenderer.numberTag("z", destination.getZ()),
                        ApisMessageRenderer.numberTag("yaw", destination.getYaw()),
                        ApisMessageRenderer.numberTag("pitch", destination.getPitch())
                    );
                }
            });
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!ApisMessageRenderer.hasAccess(sender, PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            return StandaloneWorldManager.getLoadedStandaloneWorldNames(this.server).stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
        }
        if (args.length == 2) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            final List<String> suggestions = new ArrayList<>();
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(online.getName());
                }
            }
            return suggestions;
        }
        if (sender instanceof Player player) {
            final Location loc = player.getLocation();
            final boolean isPlayerTarget = resolvePlayer(args[1]) != null;
            final int coordOffset = isPlayerTarget ? args.length - 3 : args.length - 2;
            return switch (coordOffset) {
                case 0 -> List.of(String.format(Locale.ROOT, "%.2f", loc.getX()));
                case 1 -> List.of(String.format(Locale.ROOT, "%.2f", loc.getY()));
                case 2 -> List.of(String.format(Locale.ROOT, "%.2f", loc.getZ()));
                case 3 -> List.of(String.format(Locale.ROOT, "%.1f", loc.getYaw()));
                case 4 -> List.of(String.format(Locale.ROOT, "%.1f", loc.getPitch()));
                default -> Collections.emptyList();
            };
        }
        return Collections.emptyList();
    }

    private static @Nullable Player resolvePlayer(final String name) {
        final @Nullable Player exact = Bukkit.getPlayerExact(name);
        return exact != null ? exact : Bukkit.getPlayer(name);
    }
}
