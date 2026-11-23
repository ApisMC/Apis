// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.console;

import java.util.List;
import net.minecrell.terminalconsole.TerminalConsoleAppender;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.core.pattern.PatternFormatter;
import org.apache.logging.log4j.core.pattern.PatternParser;
import org.jspecify.annotations.Nullable;

/**
 * Wraps console lines with ANSI color highlighting for {@code ERROR}/{@code FATAL} (red),
 * {@code WARN} (yellow), and {@code OK} (green), resetting ANSI formatting at the end of the line.
 */
@Plugin(name = "highlightApis", category = PatternConverter.CATEGORY)
@ConverterKeys("highlightApis")
public final class ApisHighlightConverter extends LogEventPatternConverter {

    private static final char ESC = 27;
    private static final String ERROR_COLOR = ESC + "[31;1m";
    private static final String WARN_COLOR = ESC + "[33;1m";
    private static final String OK_COLOR = ESC + "[32;1m";
    private static final String ANSI_RESET = ESC + "[m";

    private final List<PatternFormatter> formatters;

    private ApisHighlightConverter(final List<PatternFormatter> formatters) {
        super("highlightApis", null);
        this.formatters = formatters;
    }

    /**
     * Instantiates the {@code %highlightApis{pattern}} Log4j2 converter.
     *
     * @param config  active Log4j2 configuration
     * @param options inner pattern string option
     * @return configured converter or {@code null} when options are invalid
     */
    public static @Nullable ApisHighlightConverter newInstance(final Configuration config, final String[] options) {
        if (options.length != 1 || options[0] == null) {
            LOGGER.error("Incorrect options on highlightApis: expected 1 pattern argument.");
            return null;
        }
        final PatternParser parser = PatternLayout.createPatternParser(config);
        return new ApisHighlightConverter(parser.parse(options[0]));
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        this.format(event, TerminalConsoleAppender.isAnsiSupported(), toAppendTo);
    }

    /**
     * Formats an event with optional ANSI color wrapping.
     *
     * @param event         log4j2 event
     * @param ansiSupported whether ANSI escape sequences are enabled
     * @param out           destination buffer
     */
    public void format(final LogEvent event, final boolean ansiSupported, final StringBuilder out) {
        final @Nullable String color = ansiSupported ? resolveColor(event) : null;
        if (color != null) {
            out.append(color);
        }
        for (final PatternFormatter formatter : this.formatters) {
            formatter.format(event, out);
        }
        if (color != null) {
            out.append(ANSI_RESET);
        }
    }

    private static @Nullable String resolveColor(final LogEvent event) {
        final Level level = event.getLevel();
        if (level.isMoreSpecificThan(Level.ERROR)) {
            return ERROR_COLOR;
        }
        if (level.isMoreSpecificThan(Level.WARN)) {
            return WARN_COLOR;
        }
        if (ApisLevelBadgeConverter.isOkEvent(event)) {
            return OK_COLOR;
        }
        return null;
    }

    @Override
    public boolean handlesThrowable() {
        for (final PatternFormatter formatter : this.formatters) {
            if (formatter.handlesThrowable()) {
                return true;
            }
        }
        return false;
    }
}
