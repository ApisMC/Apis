package dev.ultracore.hooks;

import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class UltraCoreDiagnosticsTest {

    @AfterEach
    void tearDown() {
        UltraCoreDiagnostics.resetForTesting();
    }

    @Test
    void detectsMissingLegacyConfigExceptions() {
        final FileNotFoundException fnf = new FileNotFoundException("./bukkit.yml (No such file or directory)");
        assertEquals("bukkit.yml", UltraCoreDiagnostics.detectLegacyRootFile(fnf));
        assertTrue(UltraCoreDiagnostics.handleLegacyPluginFailure("LegacyPlugin", fnf));

        final RuntimeException wrapped = new RuntimeException(
            "Startup aborted",
            new NoSuchFileException("server.properties")
        );
        assertEquals("server.properties", UltraCoreDiagnostics.detectLegacyRootFile(wrapped));
        assertTrue(UltraCoreDiagnostics.handleLegacyPluginFailure("LegacyPlugin", wrapped));

        final IllegalStateException npeWrapped = new IllegalStateException(
            "Failed reading spigot.yml",
            new NullPointerException("section missing")
        );
        assertEquals("spigot.yml", UltraCoreDiagnostics.detectLegacyRootFile(npeWrapped));
        assertTrue(UltraCoreDiagnostics.handleLegacyPluginFailure("LegacyPlugin", npeWrapped));
    }

    @Test
    void ignoresUnrelatedPluginNpeAndNonBoundaryMatches() {
        final NullPointerException unrelatedNpe = new NullPointerException("Cannot invoke Player.getUniqueId()");
        assertNull(UltraCoreDiagnostics.detectLegacyRootFile(unrelatedNpe));
        assertFalse(UltraCoreDiagnostics.handleLegacyPluginFailure("NormalPlugin", unrelatedNpe));

        final FileNotFoundException customPluginFile = new FileNotFoundException("mybukkit.yml (No such file or directory)");
        assertNull(UltraCoreDiagnostics.detectLegacyRootFile(customPluginFile));
        assertFalse(UltraCoreDiagnostics.handleLegacyPluginFailure("NormalPlugin", customPluginFile));
    }
}
