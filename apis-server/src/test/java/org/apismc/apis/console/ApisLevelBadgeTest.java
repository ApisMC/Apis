package org.apismc.apis.console;

import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ApisLevelBadgeTest {

    private static final String ESC = String.valueOf((char) 27);

    @Test
    void consolePatternLoadsLevelBadgeAndFilePatternStaysAnsiFree() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();

        final PatternLayout consoleLayout = PatternLayout.newBuilder()
            .withConfiguration(config)
            .withCharset(StandardCharsets.UTF_8)
            .withPattern("[%d{HH:mm:ss}] %levelBadge %msg")
            .build();

        final PatternLayout fileLayout = PatternLayout.newBuilder()
            .withConfiguration(config)
            .withCharset(StandardCharsets.UTF_8)
            .withPattern("[%d{HH:mm:ss}] [%t/%level]: %stripAnsi{%msg}")
            .build();

        final LogEvent infoEvent = Log4jLogEvent.newBuilder()
            .setLoggerName("Minecraft")
            .setLevel(Level.INFO)
            .setMessage(new SimpleMessage("Preparing level \"world\""))
            .build();

        final LogEvent warnEvent = Log4jLogEvent.newBuilder()
            .setLoggerName("Minecraft")
            .setLevel(Level.WARN)
            .setMessage(new SimpleMessage(ESC + "[33mWarning detail" + ESC + "[0m"))
            .build();

        final LogEvent errorEvent = Log4jLogEvent.newBuilder()
            .setLoggerName("Minecraft")
            .setLevel(Level.ERROR)
            .setMessage(new SimpleMessage("Plugin 'LegacyPlugin' failed"))
            .build();

        final String consoleInfo = consoleLayout.toSerializable(infoEvent);
        assertTrue(consoleInfo.contains("] INFO Preparing level"));
        assertFalse(consoleInfo.contains(ESC + "["), "INFO must not carry any ANSI badge escapes");

        // Verify File pattern never contains ANSI escapes across all levels even when message had ANSI
        for (final LogEvent event : new LogEvent[] {infoEvent, warnEvent, errorEvent}) {
            final String fileLine = fileLayout.toSerializable(event);
            assertFalse(fileLine.contains(ESC + "["), "File log output must never contain ANSI escapes: " + fileLine);
        }
    }

    @Test
    void formatsWarnAndErrorWithBlackTextAndColoredPaddingOnlyWhenAnsiSupported() throws Exception {
        final Class<?> converterClass = Class.forName("org.apismc.apis.console.ApisLevelBadgeConverter");
        final var formatMethod = converterClass.getMethod("formatLevel", Level.class, boolean.class, StringBuilder.class);

        final StringBuilder warnAnsi = new StringBuilder();
        formatMethod.invoke(null, Level.WARN, true, warnAnsi);
        assertEquals(ESC + "[0;1;30;103m WARN " + ESC + "[0;33;1m", warnAnsi.toString());

        final StringBuilder errorAnsi = new StringBuilder();
        formatMethod.invoke(null, Level.ERROR, true, errorAnsi);
        assertEquals(ESC + "[0;1;30;101m ERROR " + ESC + "[0;31;1m", errorAnsi.toString());

        final StringBuilder infoAnsi = new StringBuilder();
        formatMethod.invoke(null, Level.INFO, true, infoAnsi);
        assertEquals("INFO", infoAnsi.toString());

        final StringBuilder warnPlain = new StringBuilder();
        formatMethod.invoke(null, Level.WARN, false, warnPlain);
        assertEquals("WARN", warnPlain.toString());
    }
}
