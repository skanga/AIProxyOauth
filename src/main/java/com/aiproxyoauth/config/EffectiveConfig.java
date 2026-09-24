package com.aiproxyoauth.config;

import com.aiproxyoauth.provider.ProviderId;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public record EffectiveConfig(
        Server server,
        Routing routing,
        ClientAuth clientAuth,
        Codex codex,
        Anthropic anthropic,
        Copilot copilot,
        Cors cors,
        Logging logging,
        Startup startup,
        Map<String, String> sources
) {
    public enum ProviderSelection { AUTO, CODEX, ANTHROPIC, COPILOT, BOTH, ALL, CUSTOM }
    public enum StartupCheck { OFF, CREDENTIALS, INFERENCE }
    public enum InstructionsMode { NONE, FILE, LATEST }

    public record Server(String host, int port) {}
    public record Routing(ProviderSelection provider, ProviderId defaultProvider,
                          List<ProviderId> selectedProviders, List<ProviderId> providerOrder, boolean failover) {
        public Routing {
            selectedProviders = List.copyOf(selectedProviders);
            providerOrder = List.copyOf(providerOrder);
        }
        public String selection() {
            return provider == ProviderSelection.AUTO ? null : selectedProviders.stream()
                    .map(ProviderId::wireName).collect(java.util.stream.Collectors.joining(","));
        }
        public Routing withDefault(ProviderId value) {
            return new Routing(provider, value, selectedProviders, providerOrder, failover);
        }
    }
    public record ClientAuth(Path keysFile, Path adminKeyFile, Map<String, String> environmentKeys,
                             String environmentAdminKey) {
        public ClientAuth {
            environmentKeys = Map.copyOf(environmentKeys);
        }
        public boolean enabled() {
            return keysFile != null || adminKeyFile != null || !environmentKeys.isEmpty()
                    || environmentAdminKey != null;
        }
    }
    public record Codex(List<String> models, String version, String baseUrl, Path oauthFile,
                        String oauthClientId, String oauthTokenUrl, boolean store,
                        boolean forwardPromptCacheHeaders, InstructionsMode instructionsMode,
                        Path instructionsFile, Path instructionsCacheDir) {
        public Codex { models = List.copyOf(models); }
    }
    public record Anthropic(List<String> models, String baseUrl, Path oauthFile, String tokenUrl) {
        public Anthropic { models = List.copyOf(models); }
    }
    public record Copilot(String githubHost, Path oauthFile, String oauthClientId,
                          Path tokenFile, String environmentToken, List<String> models) {
        public Copilot { models = List.copyOf(models); }
        @Override public String toString() { return "Copilot[githubHost=" + githubHost + ", credentials=<redacted>]"; }
    }
    public record Cors(List<String> origins, boolean allowAny) {
        public Cors { origins = List.copyOf(origins); }
    }
    public record Logging(boolean requests, Path directory) {}
    public record Startup(StartupCheck check) {}

    public ServerConfig legacyServerConfig(Map<String, String> keys, String adminKey) {
        String instructions = "";
        if (codex.instructionsMode() == InstructionsMode.FILE) {
            try {
                instructions = Files.readString(codex.instructionsFile());
            } catch (IOException error) {
                throw new ConfigException("Could not read codex instructions file: " + codex.instructionsFile(), error);
            }
        }
        return new ServerConfig(server.host(), server.port(), emptyToNull(codex.models()), codex.version(),
                codex.baseUrl(), codex.oauthClientId(), codex.oauthTokenUrl(), path(codex.oauthFile()), instructions,
                codex.store(), keys, adminKey, cors.allowAny(), cors.origins(), logging.requests(),
                logging.directory().toString(), codex.forwardPromptCacheHeaders(),
                switch (codex.instructionsMode()) {
                    case NONE, FILE -> "configured";
                    case LATEST -> "latest-codex";
                }, codex.instructionsCacheDir().toString());
    }

    private static List<String> emptyToNull(List<String> value) {
        return value.isEmpty() ? null : value;
    }

    private static String path(Path value) {
        return value == null ? null : value.toString();
    }
}
