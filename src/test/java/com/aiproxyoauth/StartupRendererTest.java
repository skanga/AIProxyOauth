package com.aiproxyoauth;

import com.aiproxyoauth.config.ConfigOverrides;
import com.aiproxyoauth.config.EffectiveConfig;
import com.aiproxyoauth.config.EffectiveConfigLoader;
import com.aiproxyoauth.provider.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StartupRendererTest {
    @Test
    void alwaysListsEveryModelGroupedInConfiguredProviderOrder() {
        ConfigOverrides overrides = new ConfigOverrides();
        overrides.providerOrder = "anthropic,copilot,codex";
        EffectiveConfig config = EffectiveConfigLoader.load(null, Map.of(), overrides);
        String rendered = StartupRenderer.render(config, Map.of(
                ProviderId.CODEX, new StartupRenderer.ProviderStatus("environment", List.of("gpt-one", "gpt-two"), "discovered", StartupRenderer.Check.skipped()),
                ProviderId.COPILOT, new StartupRenderer.ProviderStatus("environment", List.of("copilot-one", "copilot-two"), "discovered", StartupRenderer.Check.skipped()),
                ProviderId.ANTHROPIC, new StartupRenderer.ProviderStatus("environment", List.of("claude-one", "claude-two"), "discovered", StartupRenderer.Check.skipped())));
        assertTrue(rendered.contains("Providers:        anthropic, copilot, codex"));
        assertTrue(rendered.contains("IDs:     claude-one, claude-two"));
        assertTrue(rendered.contains("IDs:     copilot-one, copilot-two"));
        assertTrue(rendered.contains("IDs:     gpt-one, gpt-two"));
        assertTrue(rendered.indexOf("  Anthropic:") < rendered.indexOf("  Copilot:"));
        assertTrue(rendered.indexOf("  Copilot:") < rendered.indexOf("  Codex:"));
    }
    @Test
    void rendersUnifiedDualProviderBannerWithoutSecretsOrPii() {
        ConfigOverrides overrides = new ConfigOverrides();
        overrides.provider = "both";
        overrides.startupCheck = "inference";
        EffectiveConfig config = EffectiveConfigLoader.load(null, Map.of(
                "AIPROXY_CLIENT_KEYS", "app:super-secret-key"), overrides);
        Map<ProviderId, StartupRenderer.ProviderStatus> providers = Map.of(
                ProviderId.CODEX, new StartupRenderer.ProviderStatus(
                        "C:/Users/me/.codex/auth.json", List.of("gpt-a", "gpt-b"), "discovered",
                        StartupRenderer.Check.ok("gpt-a")),
                ProviderId.ANTHROPIC, new StartupRenderer.ProviderStatus(
                        "environment", List.of("claude-a"), "cache",
                        StartupRenderer.Check.failed("claude-a", "token=secret\nupstream exploded with a very long message")));

        String rendered = StartupRenderer.render(config, providers);

        assertTrue(rendered.contains("AIProxyOauth 3.0.2 started"));
        assertTrue(rendered.contains("OpenAI-compatible:"));
        assertTrue(rendered.contains("Anthropic-compatible:"));
        assertTrue(rendered.contains("Providers:        codex, anthropic"));
        assertTrue(rendered.contains("Models:  2, discovered"));
        assertTrue(rendered.contains("Ready with warnings."));
        assertFalse(rendered.contains("super-secret-key"));
        assertFalse(rendered.contains("token=secret"));
        assertFalse(rendered.contains("@"));
    }

    @Test
    void offShowsSkippedAndVerboseListsModels() {
        ConfigOverrides overrides = new ConfigOverrides();
        overrides.provider = "codex";
        overrides.startupCheck = "off";
        EffectiveConfig config = EffectiveConfigLoader.load(null, Map.of(), overrides);
        String rendered = StartupRenderer.render(config, Map.of(
                ProviderId.CODEX, new StartupRenderer.ProviderStatus(
                        "~/.codex/auth.json", List.of("gpt-a"), "configured", StartupRenderer.Check.skipped())));

        assertTrue(rendered.contains("Startup check:   off"));
        assertTrue(rendered.contains("Check:   skipped"));
        assertTrue(rendered.contains("gpt-a"));
        assertTrue(rendered.endsWith("Ready.\n"));
    }
}
