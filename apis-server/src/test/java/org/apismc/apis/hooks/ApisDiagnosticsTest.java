// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.hooks;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import net.minecraft.core.registries.Registries;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.feature.FeatureRegistry;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ApisDiagnosticsTest {

    @AfterEach
    void tearDown() {
        ApisDiagnostics.resetForTesting();
    }

    @Test
    void detectsMissingLegacyConfigExceptions() {
        final FileNotFoundException fnf = new FileNotFoundException("./bukkit.yml (No such file or directory)");
        assertEquals("bukkit.yml", ApisDiagnostics.detectLegacyRootFile(fnf));
        assertTrue(ApisDiagnostics.handleLegacyPluginFailure("LegacyPlugin", fnf));

        final RuntimeException wrapped = new RuntimeException(
            "Startup aborted",
            new NoSuchFileException("server.properties")
        );
        assertEquals("server.properties", ApisDiagnostics.detectLegacyRootFile(wrapped));
        assertTrue(ApisDiagnostics.handleLegacyPluginFailure("LegacyPlugin", wrapped));

        final IllegalStateException npeWrapped = new IllegalStateException(
            "Failed reading spigot.yml",
            new NullPointerException("section missing")
        );
        assertEquals("spigot.yml", ApisDiagnostics.detectLegacyRootFile(npeWrapped));
        assertTrue(ApisDiagnostics.handleLegacyPluginFailure("LegacyPlugin", npeWrapped));
        assertTrue(ApisDiagnostics.hasIoNotFoundCause(fnf));
        assertTrue(ApisDiagnostics.hasIoNotFoundCause(wrapped));
        assertFalse(
            ApisDiagnostics.hasIoNotFoundCause(npeWrapped),
            "NPE matches must not suppress full stacktrace output"
        );
    }

    @Test
    void ignoresUnrelatedPluginNpeAndNonBoundaryMatches() {
        final NullPointerException unrelatedNpe = new NullPointerException("Cannot invoke Player.getUniqueId()");
        assertNull(ApisDiagnostics.detectLegacyRootFile(unrelatedNpe));
        assertFalse(ApisDiagnostics.handleLegacyPluginFailure("NormalPlugin", unrelatedNpe));

        final FileNotFoundException customPluginFile = new FileNotFoundException("mybukkit.yml (No such file or directory)");
        assertNull(ApisDiagnostics.detectLegacyRootFile(customPluginFile));
        assertFalse(ApisDiagnostics.handleLegacyPluginFailure("NormalPlugin", customPluginFile));
    }

    @Test
    void serverBuildInfoReportsApisBrandAndPaperCompatibility() {
        final ServerBuildInfo info = new ServerBuildInfoImpl();
        assertEquals(ServerBuildInfo.BRAND_APIS_ID, info.brandId());
        assertEquals("Apis", info.brandName());
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_APIS_ID));
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID));
    }

    @Test
    void detectsPrunedStructureAndRecipeAdvancementErrors() {
        final ApisConfig cfg = new ApisConfig();
        cfg.chunks.structures = false;
        cfg.gameplay.recipes = false;
        FeatureRegistry.applyEarly(cfg);
        try {
            assertTrue(ApisHooks.shouldSkipUnboundRegistrationLookup(Registries.RECIPE));
            assertFalse(ApisHooks.shouldSkipUnboundRegistrationLookup(Registries.ADVANCEMENT));

            final IllegalStateException structErr = new IllegalStateException(
                "Failed to parse minecraft:nether/find_fortress from pack vanilla",
                new IllegalStateException(
                    "Failed to get element minecraft:fortress from registry minecraft:worldgen/structure"
                )
            );
            assertEquals("chunks.structures=false", ApisHooks.detectSkippedAdvancementReason(structErr));

            final IllegalStateException recipeErr = new IllegalStateException(
                "Failed to parse minecraft:recipes/tools/stone_pickaxe from pack vanilla",
                new IllegalStateException(
                    "Failed to get element minecraft:stone_pickaxe from registry minecraft:recipe"
                )
            );
            assertEquals("gameplay.recipes=false", ApisHooks.detectSkippedAdvancementReason(recipeErr));

            final IllegalStateException otherErr = new IllegalStateException("Malformed JSON syntax");
            assertNull(ApisHooks.detectSkippedAdvancementReason(otherErr));
        } finally {
            FeatureRegistry.resetForTesting();
        }
    }
}
