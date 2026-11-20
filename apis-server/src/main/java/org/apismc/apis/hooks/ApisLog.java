// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.hooks;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Emits Apis positive-status {@code OK} log events using custom Log4j2 level {@code OK} (intLevel 350).
 */
public final class ApisLog {

    /**
     * Custom Log4j2 level between {@code WARN} (300) and {@code INFO} (400) so standard {@code info}-threshold
     * appenders pass it through while console patterns render the green {@code OK} badge.
     */
    public static final Level OK_LEVEL = Level.forName("OK", 350);

    private ApisLog() {
    }

    /**
     * Logs a positive/completed status message at level {@code OK} to a Log4j2 logger.
     *
     * @param logger  Log4j2 logger
     * @param message parameterized Log4j2 message template
     * @param params  message arguments
     */
    public static void ok(final Logger logger, final String message, final @Nullable Object... params) {
        logger.log(OK_LEVEL, message, params);
    }

    /**
     * Logs a positive/completed status message at level {@code OK} through the Log4j2 logger matching an SLF4J logger.
     *
     * @param logger  SLF4J logger whose name identifies the target Log4j2 logger
     * @param message parameterized Log4j2 message template
     * @param params  message arguments
     */
    public static void ok(final org.slf4j.Logger logger, final String message, final @Nullable Object... params) {
        LogManager.getLogger(logger.getName()).log(OK_LEVEL, message, params);
    }
}
