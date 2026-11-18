package org.apismc.apis.command;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.world.StandaloneWorldManager;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Overrides {@code /setworldspawn} in standalone mode to write fractional spawn coordinates and
 * camera angles directly into {@code worlds/<world>/world.yml}.
 */
public final class SetWorldSpawnOverrideCommand extends Command {

    public static final String PERMISSION = "apis.command.setspawn";

    public SetWorldSpawnOverrideCommand() {
        super(
            "setworldspawn",
            "Updates the spawn location and orientation in worlds/<world>/world.yml",
            "/setworldspawn [<x> <y> <z>] [<yaw> <pitch>]",
            List.of()
        );
        this.setPermission(PERMISSION);
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        final ApisConfig.CommandMessagesSection msg = ApisMessageRenderer.commandMessages();
        if (!ApisMessageRenderer.hasAccess(sender, PERMISSION)) {
            ApisMessageRenderer.send(sender, msg.noPermission, ApisConfig.CommandMessagesSection.DEFAULT_NO_PERMISSION);
            return true;
        }
        if (args.length != 0 && args.length != 3 && args.length != 5) {
            ApisMessageRenderer.send(
                sender,
                msg.invalidSyntaxSetWorldSpawn,
                ApisConfig.CommandMessagesSection.DEFAULT_INVALID_SYNTAX_SETWORLDSPAWN
            );
            return true;
        }
        if (!(sender instanceof Player player)) {
            ApisMessageRenderer.send(sender, msg.playerOnly, ApisConfig.CommandMessagesSection.DEFAULT_PLAYER_ONLY);
            return true;
        }

        final Location current = player.getLocation();
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        if (args.length == 0) {
            x = current.getX();
            y = current.getY();
            z = current.getZ();
            yaw = current.getYaw();
            pitch = current.getPitch();
        } else {
            final @Nullable Double parsedX = ApisMessageRenderer.parseFiniteDouble(args[0]);
            if (parsedX == null) {
                ApisMessageRenderer.sendInvalidNumber(sender, msg, args[0]);
                return true;
            }
            final @Nullable Double parsedY = ApisMessageRenderer.parseFiniteDouble(args[1]);
            if (parsedY == null) {
                ApisMessageRenderer.sendInvalidNumber(sender, msg, args[1]);
                return true;
            }
            final @Nullable Double parsedZ = ApisMessageRenderer.parseFiniteDouble(args[2]);
            if (parsedZ == null) {
                ApisMessageRenderer.sendInvalidNumber(sender, msg, args[2]);
                return true;
            }
            x = parsedX;
            y = parsedY;
            z = parsedZ;
            if (args.length == 5) {
                final @Nullable Float parsedYaw = ApisMessageRenderer.parseFiniteFloat(args[3]);
                if (parsedYaw == null) {
                    ApisMessageRenderer.sendInvalidNumber(sender, msg, args[3]);
                    return true;
                }
                final @Nullable Float parsedPitch = ApisMessageRenderer.parseFiniteFloat(args[4]);
                if (parsedPitch == null) {
                    ApisMessageRenderer.sendInvalidNumber(sender, msg, args[4]);
                    return true;
                }
                yaw = parsedYaw;
                pitch = parsedPitch;
            } else {
                yaw = current.getYaw();
                pitch = current.getPitch();
            }
        }

        StandaloneWorldManager.updateWorldSpawn(player.getWorld(), x, y, z, yaw, pitch);
        ApisMessageRenderer.send(
            sender,
            msg.setWorldSpawnSuccess,
            ApisConfig.CommandMessagesSection.DEFAULT_SETWORLDSPAWN_SUCCESS,
            Placeholder.unparsed("world", player.getWorld().getName()),
            ApisMessageRenderer.numberTag("x", x),
            ApisMessageRenderer.numberTag("y", y),
            ApisMessageRenderer.numberTag("z", z),
            ApisMessageRenderer.numberTag("yaw", yaw),
            ApisMessageRenderer.numberTag("pitch", pitch)
        );
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!ApisMessageRenderer.hasAccess(sender, PERMISSION) || !(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        final Location loc = player.getLocation();
        return switch (args.length) {
            case 1 -> List.of(String.format(Locale.ROOT, "%.2f", loc.getX()));
            case 2 -> List.of(String.format(Locale.ROOT, "%.2f", loc.getY()));
            case 3 -> List.of(String.format(Locale.ROOT, "%.2f", loc.getZ()));
            case 4 -> List.of(String.format(Locale.ROOT, "%.1f", loc.getYaw()));
            case 5 -> List.of(String.format(Locale.ROOT, "%.1f", loc.getPitch()));
            default -> Collections.emptyList();
        };
    }
}
