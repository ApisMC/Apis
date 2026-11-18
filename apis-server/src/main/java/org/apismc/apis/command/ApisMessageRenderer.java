package org.apismc.apis.command;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.apismc.apis.config.ApisConfig;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * Renders MiniMessage command responses configured in {@code config/apis.yml} with mute-on-empty support.
 */
public final class ApisMessageRenderer {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private ApisMessageRenderer() {
    }

    /**
     * Renders a configured MiniMessage template, returning {@code null} when muted via an empty/blank string.
     *
     * @param configuredTemplate template from {@code config/apis.yml} (nullable if missing)
     * @param fallbackTemplate   built-in default template used when {@code configuredTemplate} is {@code null}
     * @param resolvers          placeholder tag resolvers
     * @return rendered {@link Component}, or {@code null} if muted
     */
    public static @Nullable Component render(
        final @Nullable String configuredTemplate,
        final String fallbackTemplate,
        final TagResolver... resolvers
    ) {
        final String raw = configuredTemplate != null ? configuredTemplate : fallbackTemplate;
        if (raw.isBlank()) {
            return null;
        }
        try {
            return MINI_MESSAGE.deserialize(raw, resolvers);
        } catch (Exception _) {
            return Component.text(raw);
        }
    }

    /**
     * Renders and sends a message to {@code sender} unless the template is blank/empty.
     *
     * @param sender             command sender
     * @param configuredTemplate configured template
     * @param fallbackTemplate   fallback default template
     * @param resolvers          placeholder tag resolvers
     */
    public static void send(
        final CommandSender sender,
        final @Nullable String configuredTemplate,
        final String fallbackTemplate,
        final TagResolver... resolvers
    ) {
        final @Nullable Component message = render(configuredTemplate, fallbackTemplate, resolvers);
        if (message != null) {
            sender.sendMessage(message);
        }
    }

    /**
     * Returns current command message config section safely.
     *
     * @return command messages section
     */
    public static ApisConfig.CommandMessagesSection commandMessages() {
        return ApisConfig.get().messages.commands;
    }

    /**
     * Checks whether sender is OP or explicitly holds the required permission node.
     *
     * @param sender     command sender
     * @param permission permission node
     * @return {@code true} if allowed
     */
    public static boolean hasAccess(final CommandSender sender, final String permission) {
        return sender.isOp() || sender.hasPermission(permission);
    }

    /**
     * Parses a finite {@code double} or returns {@code null}.
     *
     * @param raw raw CLI argument
     * @return parsed finite double, or {@code null}
     */
    public static @Nullable Double parseFiniteDouble(final String raw) {
        try {
            final double parsed = Double.parseDouble(raw);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Parses a finite {@code float} or returns {@code null}.
     *
     * @param raw raw CLI argument
     * @return parsed finite float, or {@code null}
     */
    public static @Nullable Float parseFiniteFloat(final String raw) {
        try {
            final float parsed = Float.parseFloat(raw);
            return Float.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Sends the configured invalid-number response for {@code rawInput}.
     *
     * @param sender   command sender
     * @param msg      command messages config
     * @param rawInput invalid user token
     */
    public static void sendInvalidNumber(
        final CommandSender sender,
        final ApisConfig.CommandMessagesSection msg,
        final String rawInput
    ) {
        send(
            sender,
            msg.invalidNumber,
            ApisConfig.CommandMessagesSection.DEFAULT_INVALID_NUMBER,
            Placeholder.unparsed("input", rawInput)
        );
    }

    /**
     * Creates a formatted numeric placeholder tag.
     *
     * @param key   placeholder key
     * @param value numeric value
     * @return tag resolver
     */
    public static TagResolver numberTag(final String key, final double value) {
        return Placeholder.unparsed(key, String.format(Locale.ROOT, "%.2f", value));
    }
}
