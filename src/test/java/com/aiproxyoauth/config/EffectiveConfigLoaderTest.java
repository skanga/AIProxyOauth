package com.aiproxyoauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveConfigLoaderTest {
    @TempDir Path temporary;

    @Test
    void loadsYamlAndResolvesPathsRelativeToConfigFile() throws Exception {
        Path configFile = temporary.resolve("conf/aiproxy.yaml");
        Files.createDirectories(configFile.getParent());
        Files.createDirectories(temporary.resolve("secrets"));
        Files.writeString(temporary.resolve("secrets/keys.txt"), "app:sk-proxy-test\n");
        Files.writeString(configFile, """
                server:
                  host: 127.0.0.1
                  port: 9090
                routing:
                  provider: both
                  default_provider: anthropic
                client_auth:
                  keys_file: ../secrets/keys.txt
                codex:
                  models: [gpt-test]
                  base_url: https://example.test/codex/
                  instructions:
                    mode: none
                anthropic:
                  models: [claude-test]
                  base_url: https://example.test/v1/
                startup:
                  check: off
                """);

        EffectiveConfig config = EffectiveConfigLoader.load(configFile, Map.of(), new ConfigOverrides());

        assertEquals(9090, config.server().port());
        assertEquals(EffectiveConfig.ProviderSelection.BOTH, config.routing().provider());
        assertEquals("https://example.test/codex", config.codex().baseUrl());
        assertEquals("https://example.test", config.anthropic().baseUrl());
        assertEquals(configFile.getParent().resolve("../secrets/keys.txt").normalize(), config.clientAuth().keysFile());
        assertEquals(EffectiveConfig.StartupCheck.OFF, config.startup().check());
    }

    @Test
    void cliOverridesEnvironmentWhichOverridesYaml() throws Exception {
        Path configFile = temporary.resolve("aiproxy.yaml");
        Files.writeString(configFile, "server:\n  port: 7000\n");
        ConfigOverrides cli = new ConfigOverrides();
        cli.port = 9000;

        EffectiveConfig config = EffectiveConfigLoader.load(
                configFile, Map.of("AIPROXY_PORT", "8000", "AIPROXY_HOST", "localhost"), cli);

        assertEquals(9000, config.server().port());
        assertEquals("localhost", config.server().host());
        assertEquals("cli", config.sources().get("server.port"));
        assertEquals("environment", config.sources().get("server.host"));
    }

    @Test
    void rejectsUnknownKeysInlineSecretsAndInsecureRemoteUrls() throws Exception {
        Path unknown = temporary.resolve("unknown.yaml");
        Files.writeString(unknown, "server:\n  mystery: true\n");
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(unknown, Map.of(), new ConfigOverrides()));

        Path secret = temporary.resolve("secret.yaml");
        Files.writeString(secret, "client_auth:\n  keys: [secret]\n");
        ConfigException inline = assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(secret, Map.of(), new ConfigOverrides()));
        assertTrue(inline.getMessage().contains("inline"));

        ConfigOverrides overrides = new ConfigOverrides();
        overrides.codexBaseUrl = "http://example.com/codex";
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), overrides));
    }

    @Test
    void allowsLoopbackHttpAndValidatesCorsAndInstructionFile() throws Exception {
        ConfigOverrides loopback = new ConfigOverrides();
        loopback.codexBaseUrl = "http://127.0.0.1:8181/codex/";
        loopback.anthropicBaseUrl = "http://localhost:8182/v1";
        EffectiveConfig config = EffectiveConfigLoader.load(null, Map.of(), loopback);
        assertEquals("http://127.0.0.1:8181/codex", config.codex().baseUrl());
        assertEquals("http://localhost:8182", config.anthropic().baseUrl());

        ConfigOverrides cors = new ConfigOverrides();
        cors.corsOrigins = List.of("https://example.com/path");
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), cors));

        cors.corsOrigins = List.of("http://example.com");
        assertEquals(List.of("http://example.com"),
                EffectiveConfigLoader.load(null, Map.of(), cors).cors().origins());

        ConfigOverrides file = new ConfigOverrides();
        file.codexInstructionsMode = "file";
        file.codexInstructionsFile = temporary.resolve("missing.txt").toString();
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), file));
    }

    @Test
    void wildcardCorsRequiresClientAuthentication() {
        ConfigOverrides overrides = new ConfigOverrides();
        overrides.allowAnyCors = true;
        ConfigException error = assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), overrides));
        assertTrue(error.getMessage().contains("client authentication"));
    }
}
