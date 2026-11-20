// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.console;

import net.minecrell.terminalconsole.TerminalConsoleAppender;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * Renders padded 16-color ANSI console badges (bold black text on colored background)
 * for WARN and ERROR/FATAL levels while keeping INFO and other levels unbadged.
 * Intended strictly for the TerminalConsole appender; file appenders do not reference this converter.
 */
@Plugin(name = "levelBadge", category = PatternConverter.CATEGORY)
@ConverterKeys("levelBadge")
public final class ApisLevelBadgeConverter extends LogEventPatternConverter {

    public static final Level OK_LEVEL = Level.forName("OK", 350);

    private static final char ESC = 27;
    private static final String ERROR_BADGE_PREFIX = ESC + "[0;1;30;101m ";
    private static final String ERROR_TEXT_RESTORE = " " + ESC + "[0;31;1m";
    private static final String WARN_BADGE_PREFIX = ESC + "[0;1;30;103m ";
    private static final String WARN_TEXT_RESTORE = " " + ESC + "[0;33;1m";
    private static final String OK_BADGE_PREFIX = ESC + "[0;1;30;102m ";
    private static final String OK_TEXT_RESTORE = " " + ESC + "[0;32;1m";

    private ApisLevelBadgeConverter() {
        super("levelBadge", "levelBadge");
    }

    /**
     * Factory invoked by Log4j2 PatternLayout when parsing {@code %levelBadge}.
     *
     * @param options pattern options (unused)
     * @return converter instance
     */
    public static ApisLevelBadgeConverter newInstance(final String[] options) {
        return new ApisLevelBadgeConverter();
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final boolean ansiSupported = TerminalConsoleAppender.isAnsiSupported();
        if (isOkMarker(event) && !event.getLevel().isMoreSpecificThan(Level.WARN)) {
            if (!ansiSupported) {
                toAppendTo.append("OK");
            } else {
                toAppendTo.append(OK_BADGE_PREFIX).append("OK").append(OK_TEXT_RESTORE);
            }
            return;
        }
        formatLevel(event.getLevel(), ansiSupported, toAppendTo);
    }

    /**
     * Returns whether an event should render with the {@code OK} green badge and styling.
     *
     * @param event log event
     * @return {@code true} if the event has level {@code OK} or an {@code OK} marker below {@code WARN}
     */
    public static boolean isOkEvent(final LogEvent event) {
        if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
            return false;
        }
        return "OK".equalsIgnoreCase(event.getLevel().name()) || isOkMarker(event);
    }

    private static boolean isOkMarker(final LogEvent event) {
        return event.getMarker() != null && "OK".equalsIgnoreCase(event.getMarker().getName());
    }

    /**
     * Formats the log level badge into {@code out}.
     *
     * @param level         log level
     * @param ansiSupported whether ANSI escapes are supported on the console
     * @param out           target string builder
     */
    public static void formatLevel(final Level level, final boolean ansiSupported, final StringBuilder out) {
        final String name = level.name();
        if (!ansiSupported) {
            out.append(name);
            return;
        }
        if (level.isMoreSpecificThan(Level.ERROR)) {
            out.append(ERROR_BADGE_PREFIX).append(name).append(ERROR_TEXT_RESTORE);
            return;
        }
        if (level.isMoreSpecificThan(Level.WARN)) {
            out.append(WARN_BADGE_PREFIX).append(name).append(WARN_TEXT_RESTORE);
            return;
        }
        if ("OK".equalsIgnoreCase(name)) {
            out.append(OK_BADGE_PREFIX).append("OK").append(OK_TEXT_RESTORE);
            return;
        }
        out.append(name);
    }
}
