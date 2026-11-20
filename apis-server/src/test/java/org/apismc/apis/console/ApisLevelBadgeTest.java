// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.console;

import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apismc.apis.hooks.ApisLog;
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

        final LogEvent okEvent = Log4jLogEvent.newBuilder()
            .setLoggerName("Minecraft")
            .setLevel(ApisLog.OK_LEVEL)
            .setMessage(new SimpleMessage("Done (1.234s)! For help, type \"help\""))
            .build();

        final String consoleInfo = consoleLayout.toSerializable(infoEvent);
        assertTrue(consoleInfo.contains("] INFO Preparing level"));
        assertFalse(consoleInfo.contains(ESC + "["), "INFO must not carry any ANSI badge escapes");

        final String fileOk = fileLayout.toSerializable(okEvent);
        assertTrue(fileOk.contains("/OK]: Done (1.234s)!"), "File log should show OK level: " + fileOk);

        // Verify File pattern never contains ANSI escapes across all levels even when message had ANSI
        for (final LogEvent event : new LogEvent[] {infoEvent, warnEvent, errorEvent, okEvent}) {
            final String fileLine = fileLayout.toSerializable(event);
            assertFalse(fileLine.contains(ESC + "["), "File log output must never contain ANSI escapes: " + fileLine);
        }
    }

    @Test
    void formatsWarnErrorAndOkBadgesOnlyWhenAnsiSupported() throws Exception {
        final Class<?> converterClass = Class.forName("org.apismc.apis.console.ApisLevelBadgeConverter");
        final var formatMethod = converterClass.getMethod("formatLevel", Level.class, boolean.class, StringBuilder.class);

        final StringBuilder warnAnsi = new StringBuilder();
        formatMethod.invoke(null, Level.WARN, true, warnAnsi);
        assertEquals(ESC + "[0;1;30;103m WARN " + ESC + "[0;33;1m", warnAnsi.toString());

        final StringBuilder errorAnsi = new StringBuilder();
        formatMethod.invoke(null, Level.ERROR, true, errorAnsi);
        assertEquals(ESC + "[0;1;30;101m ERROR " + ESC + "[0;31;1m", errorAnsi.toString());

        final StringBuilder okAnsi = new StringBuilder();
        formatMethod.invoke(null, ApisLog.OK_LEVEL, true, okAnsi);
        assertEquals(ESC + "[0;1;30;102m OK " + ESC + "[0;32;1m", okAnsi.toString());

        final StringBuilder infoAnsi = new StringBuilder();
        formatMethod.invoke(null, Level.INFO, true, infoAnsi);
        assertEquals("INFO", infoAnsi.toString());

        final StringBuilder warnPlain = new StringBuilder();
        formatMethod.invoke(null, Level.WARN, false, warnPlain);
        assertEquals("WARN", warnPlain.toString());

        final StringBuilder okPlain = new StringBuilder();
        formatMethod.invoke(null, ApisLog.OK_LEVEL, false, okPlain);
        assertEquals("OK", okPlain.toString());
    }

    @Test
    void highlightApisWrapsOkAndWarnLinesAndResetsAnsi() throws Exception {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();
        final Class<?> highlightClass = Class.forName("org.apismc.apis.console.ApisHighlightConverter");
        final var newInstance = highlightClass.getMethod("newInstance", Configuration.class, String[].class);
        final var formatEvent = highlightClass.getMethod("format", LogEvent.class, boolean.class, StringBuilder.class);
        final Object converter = newInstance.invoke(null, config, new String[] {"[%d{HH:mm:ss}] %level %msg"});

        final LogEvent okLevelEvent = Log4jLogEvent.newBuilder()
            .setLoggerName("Minecraft")
            .setLevel(ApisLog.OK_LEVEL)
            .setMessage(new SimpleMessage("Blocked server call"))
            .build();
        final StringBuilder okHighlighted = new StringBuilder();
        formatEvent.invoke(converter, okLevelEvent, true, okHighlighted);
        assertTrue(okHighlighted.toString().startsWith(ESC + "[32;1m"));
        assertTrue(okHighlighted.toString().endsWith(ESC + "[m"));

        final LogEvent okMarkerEvent = Log4jLogEvent.newBuilder()
            .setLoggerName("Minecraft")
            .setLevel(Level.INFO)
            .setMarker(MarkerManager.getMarker("OK"))
            .setMessage(new SimpleMessage("Ready"))
            .build();
        final StringBuilder markerHighlighted = new StringBuilder();
        formatEvent.invoke(converter, okMarkerEvent, true, markerHighlighted);
        assertTrue(markerHighlighted.toString().startsWith(ESC + "[32;1m"));
        assertTrue(markerHighlighted.toString().endsWith(ESC + "[m"));

        final StringBuilder plainOk = new StringBuilder();
        formatEvent.invoke(converter, okLevelEvent, false, plainOk);
        assertFalse(plainOk.toString().contains(ESC + "["));
        assertTrue(plainOk.toString().contains("OK Blocked server call"));
    }
}
