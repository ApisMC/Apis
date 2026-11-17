package dev.ultracore.console;

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
@ConverterKeys({"levelBadge"})
public final class UltraCoreLevelBadgeConverter extends LogEventPatternConverter {

    private static final String ERROR_BADGE_PREFIX = "\u001b[0;1;30;101m ";
    private static final String ERROR_TEXT_RESTORE = " \u001b[0;31;1m";
    private static final String WARN_BADGE_PREFIX = "\u001b[0;1;30;103m ";
    private static final String WARN_TEXT_RESTORE = " \u001b[0;33;1m";

    private UltraCoreLevelBadgeConverter() {
        super("levelBadge", "levelBadge");
    }

    /**
     * Factory invoked by Log4j2 PatternLayout when parsing {@code %levelBadge}.
     *
     * @param options pattern options (unused)
     * @return converter instance
     */
    public static UltraCoreLevelBadgeConverter newInstance(final String[] options) {
        return new UltraCoreLevelBadgeConverter();
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        formatLevel(event.getLevel(), TerminalConsoleAppender.isAnsiSupported(), toAppendTo);
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
        out.append(name);
    }
}
