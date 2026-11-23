// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.metrics;

import com.destroystokyo.paper.Metrics;
import java.util.UUID;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.feature.FeatureRegistry;
import org.apismc.apis.hooks.ApisHooks;
import org.bukkit.support.environment.Normal;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ApisBstatsMetricsTest {

    @AfterEach
    void resetFeatures() {
        FeatureRegistry.applyEarly(new ApisConfig());
    }

    @Test
    void buildsV2ServerImplementationPayloadForServiceId35466WithoutPaperBranding() {
        assertEquals(35466, Metrics.APIS_SERVICE_ID);
        assertEquals("https://bstats.org/api/v2/data/server-implementation", Metrics.URL);

        final String serverUuid = UUID.randomUUID().toString();
        final Metrics metrics = Metrics.createDetached(Metrics.APIS_SERVICE_ID, serverUuid);
        metrics.addCustomChart(new Metrics.SimplePie("minecraft_version", () -> "1.21.11"));
        metrics.addCustomChart(new Metrics.SingleLineChart("players", () -> 4));
        metrics.addCustomChart(
            new Metrics.SimplePie(
                "apis_version",
                () -> Metrics.PaperMetrics.formatApisVersion("1.21.11", "git-Apis-e90c812")
            )
        );

        final JSONObject payload = metrics.buildPayload();
        assertEquals(serverUuid, payload.get("serverUUID"));
        assertEquals(Metrics.METRICS_VERSION, payload.get("metricsVersion"));
        assertNotNull(payload.get("osName"));
        assertNotNull(payload.get("osArch"));
        assertNotNull(payload.get("osVersion"));
        assertTrue(((Number) payload.get("coreCount")).intValue() >= 1);
        assertFalse(payload.containsKey("plugins"), "v2 schema must use 'service' object instead of v1 'plugins' array");

        final JSONObject service = (JSONObject) payload.get("service");
        assertNotNull(service);
        assertEquals(35466, service.get("id"));
        assertNotNull(service.get("pluginVersion"));

        final JSONArray customCharts = (JSONArray) service.get("customCharts");
        assertEquals(3, customCharts.size());
        final String serialized = payload.toJSONString();
        assertFalse(serialized.contains("Paper"), "Payload must not carry upstream Paper branding: " + serialized);
        assertTrue(serialized.contains("\"apis_version\""));
        assertTrue(serialized.contains("git-Apis-1.21.11-e90c812"));
    }

    @Test
    void formatsApisVersionSafelyWhenImplVersionMissingOrHyphenated() {
        assertEquals("unknown", Metrics.PaperMetrics.formatApisVersion("1.21.11", null));
        assertEquals("unknown", Metrics.PaperMetrics.formatApisVersion("1.21.11", ""));
        assertEquals("git-Apis-1.21.11-abc1234", Metrics.PaperMetrics.formatApisVersion("1.21.11", "1.21.11-RELEASE-abc1234"));
    }

    @Test
    void bstatsKillSwitchReflectsApisConfig() {
        final ApisConfig disabledConfig = new ApisConfig();
        disabledConfig.network.analytics.bstats = false;
        FeatureRegistry.applyEarly(disabledConfig);
        assertTrue(ApisHooks.isBstatsDisabled());

        final ApisConfig enabledConfig = new ApisConfig();
        enabledConfig.network.analytics.bstats = true;
        FeatureRegistry.applyEarly(enabledConfig);
        assertFalse(ApisHooks.isBstatsDisabled());
    }
}
