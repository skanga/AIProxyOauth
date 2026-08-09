package com.aiproxyoauth;

import com.aiproxyoauth.auth.AuthFileResolver;
import com.aiproxyoauth.auth.AuthLoader;
import com.aiproxyoauth.auth.AuthManager;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.config.ConfigException;
import com.aiproxyoauth.config.ConfigOverrides;
import com.aiproxyoauth.config.EffectiveConfig;
import com.aiproxyoauth.config.EffectiveConfigLoader;
import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.AnthropicModelResolver;
import com.aiproxyoauth.model.CodexModelCatalog;
import com.aiproxyoauth.model.CompositeModelCatalog;
import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.model.ProviderModelCatalog;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicAuthCommands;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicAuthManager;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicCredentialPaths;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicCredentialStore;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicOAuthClient;
import com.aiproxyoauth.server.ApiKeyStore;
import com.aiproxyoauth.server.ProxyServer;
import com.aiproxyoauth.sse.SseParser;
import com.aiproxyoauth.sse.ServerSentEvent;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.ApiKeyUtils;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Command(
        name = "aiproxy",
        description = "OAuth proxy exposing OpenAI-compatible and Anthropic-compatible APIs.",
        mixinStandardHelpOptions = true,
        version = "AIProxyOauth 2.0.0",
        subcommands = {
                AIProxyOauth.ServeCommand.class,
                AIProxyOauth.AuthCommand.class,
                AIProxyOauth.KeyCommand.class,
                AIProxyOauth.ConfigCommand.class,
                AIProxyOauth.DoctorCommand.class
        }
)
public class AIProxyOauth implements Callable<Integer> {

    @Mixin
    private ServeOptions rootOptions = new ServeOptions();

    private final Supplier<Map<String, String>> environment;
    private ServeOptions activeOptions;

    public AIProxyOauth() {
        this(System::getenv);
    }

    AIProxyOauth(Supplier<Map<String, String>> environment) {
        this.environment = environment;
    }

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        return runServe(rootOptions);
    }

    private Integer runServe(ServeOptions options) throws Exception {
        return runProxy(options, false);
    }

    private Integer runProxy(ServeOptions options, boolean doctorMode) throws Exception {
        activeOptions = options;
        EffectiveConfig effective;
        try {
            effective = EffectiveConfigLoader.load(options.configPath(), environment.get(), options.toOverrides());
        } catch (ConfigException error) {
            spec.commandLine().getErr().println("Configuration error: " + error.getMessage());
            return 2;
        }
        Map<String, String> configuredKeys = new HashMap<>(effective.clientAuth().environmentKeys());
        if (effective.clientAuth().keysFile() != null) {
            Files.readAllLines(effective.clientAuth().keysFile()).stream()
                    .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> ApiKeyUtils.parseKeyEntry(line, configuredKeys));
            if (configuredKeys.isEmpty()) throw new ConfigException("client keys file contains no keys");
        }
        String configuredAdmin = effective.clientAuth().environmentAdminKey();
        if (effective.clientAuth().adminKeyFile() != null) {
            configuredAdmin = Files.readString(effective.clientAuth().adminKeyFile()).strip();
            if (configuredAdmin.isBlank()) throw new ConfigException("admin client key file is empty");
        }
        ServerConfig config = effective.legacyServerConfig(configuredKeys, configuredAdmin);
        if (config.fullRequestLogging()) {
            System.err.println("WARNING: full request logging is enabled. Request/response bodies may contain prompts, "
                    + "tool outputs, file paths, and other sensitive data. Authorization and API key headers are "
                    + "redacted, but logs should still be protected.");
        }

        String codexAuthPath = findExistingAuthFile(config.oauthFilePath());
        Path anthropicCredentialPath = effective.anthropic().oauthFile();
        boolean anthropicCredentialAvailable =
                hasText(environment.get().get("CLAUDE_CODE_OAUTH_TOKEN"))
                        || Files.isRegularFile(anthropicCredentialPath);
        Set<ProviderId> enabledProviders;
        ProviderId effectiveDefaultProvider;
        try {
            String selectedProviders = switch (effective.routing().provider()) {
                case AUTO -> null;
                case CODEX -> "codex";
                case ANTHROPIC -> "anthropic";
                case BOTH -> "codex,anthropic";
            };
            enabledProviders = ProviderStartupResolver.resolve(
                    selectedProviders, codexAuthPath != null, anthropicCredentialAvailable);
            String requestedDefault = "default".equals(effective.sources().get("routing.default_provider"))
                    ? null : effective.routing().defaultProvider().wireName();
            effectiveDefaultProvider = ProviderStartupResolver.resolveDefault(
                    requestedDefault, enabledProviders);
        } catch (IllegalArgumentException error) {
            spec.commandLine().getErr().println(error.getMessage());
            return 1;
        }

        HttpClient authHttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        AuthManager authManager = new AuthManager(config, authHttpClient);
        AuthLoader.AuthResult authResult = null;
        String codexCredentialError = null;
        if (enabledProviders.contains(ProviderId.CODEX)) {
            try {
                authResult = authManager.ensureFresh();
            } catch (Exception error) {
                codexCredentialError = error.getMessage();
            }
        }

        CodexHttpClient httpClient = new CodexHttpClient(config, authManager);
        ModelResolver modelResolver = new ModelResolver(httpClient, config.models(), config.codexVersion());
        List<ProviderModelCatalog> catalogs = new ArrayList<>();
        if (enabledProviders.contains(ProviderId.CODEX)) {
            catalogs.add(new CodexModelCatalog(modelResolver));
        }

        AnthropicCredentialStore anthropicStore = null;
        AnthropicModelResolver anthropicResolver = null;
        AnthropicCompatibilityProfile activeAnthropicProfile = null;
        AnthropicHttpClient activeAnthropicHttpClient = null;
        AnthropicAuthManager activeAnthropicAuth = null;
        if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
            AnthropicCompatibilityProfile profile = anthropicProfile(effective);
            activeAnthropicProfile = profile;
            anthropicStore = AnthropicCredentialStore.open(anthropicCredentialPath);
            AnthropicAuthManager anthropicAuth = new AnthropicAuthManager(
                    anthropicStore,
                    new AnthropicOAuthClient(profile, authHttpClient),
                    Clock.systemUTC(),
                    environment.get()
            );
            activeAnthropicAuth = anthropicAuth;
            AnthropicHttpClient anthropicHttpClient = new AnthropicHttpClient(
                    profile,
                    authHttpClient,
                    anthropicAuth,
                    new RequestLogger(
                            config.fullRequestLogging(),
                            Path.of(config.requestLogDir())
                    )
            );
            activeAnthropicHttpClient = anthropicHttpClient;
            anthropicResolver = new AnthropicModelResolver(
                    anthropicHttpClient,
                    profile,
                    effective.anthropic().models(),
                    Clock.systemUTC()
            );
            catalogs.add(anthropicResolver);
        }
        ModelCatalog modelCatalog = catalogs.size() == 1
                ? catalogs.getFirst()
                : new CompositeModelCatalog(catalogs);

        // Discover models upfront
        List<String> availableModels = resolveAvailableModels(modelCatalog);

        Map<String, String> inlineKeys = new HashMap<>(effective.clientAuth().environmentKeys());
        if (config.adminKey() != null) inlineKeys.remove(config.adminKey());
        String explicitAdminKey = config.adminKey();
        String keysFile = effective.clientAuth().keysFile() == null ? null : effective.clientAuth().keysFile().toString();
        ApiKeyStore apiKeyStore = new ApiKeyStore(inlineKeys, keysFile, explicitAdminKey);
        if (keysFile != null) {
            apiKeyStore.reload();
        }
        apiKeyStore.startWatching();

        // Start server
        UsageTracker usageTracker = new UsageTracker();
        ProxyServer server = new ProxyServer(
                config, httpClient, modelCatalog, usageTracker, apiKeyStore,
                activeAnthropicHttpClient, activeAnthropicProfile, effectiveDefaultProvider);
        server.start();

        Map<ProviderId, StartupRenderer.Check> checks = new HashMap<>();
        if (effective.startup().check() == EffectiveConfig.StartupCheck.OFF) {
            enabledProviders.forEach(provider -> checks.put(provider, StartupRenderer.Check.skipped()));
        } else if (effective.startup().check() == EffectiveConfig.StartupCheck.CREDENTIALS) {
            if (enabledProviders.contains(ProviderId.CODEX)) {
                checks.put(ProviderId.CODEX, authResult != null
                        ? StartupRenderer.Check.ok("credentials")
                        : StartupRenderer.Check.failed("credentials", codexCredentialError));
            }
            if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
                try {
                    activeAnthropicAuth.accessToken();
                    checks.put(ProviderId.ANTHROPIC, StartupRenderer.Check.ok("credentials"));
                } catch (Exception error) {
                    checks.put(ProviderId.ANTHROPIC, StartupRenderer.Check.failed("credentials", error.getMessage()));
                }
            }
        } else {
            String startupKey = startupClientKey(effective);
            try (HttpClient startupProbeClient = HttpClient.newHttpClient()) {
                if (enabledProviders.contains(ProviderId.CODEX)) {
                    List<String> codexModels = resolveAvailableModels(modelCatalog, ProviderId.CODEX);
                    StartupProbeResult probe = verifyChatCompletionThroughProxy(config,
                            codexModels.stream().map(model -> "codex/" + model).toList(), startupKey, startupProbeClient);
                    checks.put(ProviderId.CODEX, check(probe));
                }
                if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
                    StartupProbeResult probe = verifyAnthropicThroughProxy(config,
                            resolveAvailableModels(modelCatalog, ProviderId.ANTHROPIC), startupKey, startupProbeClient);
                    checks.put(ProviderId.ANTHROPIC, check(probe));
                }
            }
        }

        Map<ProviderId, StartupRenderer.ProviderStatus> statuses = new java.util.LinkedHashMap<>();
        if (enabledProviders.contains(ProviderId.CODEX)) {
            List<String> providerModels = resolveAvailableModels(modelCatalog, ProviderId.CODEX);
            statuses.put(ProviderId.CODEX, new StartupRenderer.ProviderStatus(
                    authResult != null && authResult.sourcePath() != null ? authResult.sourcePath() : codexAuthPath,
                    providerModels, codexModelSource(modelResolver),
                    checks.get(ProviderId.CODEX)));
        }
        if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
            List<String> providerModels = resolveAvailableModels(modelCatalog, ProviderId.ANTHROPIC);
            String modelSource = anthropicModelSource(anthropicResolver, effective);
            String authSource = hasText(environment.get().get("CLAUDE_CODE_OAUTH_TOKEN"))
                    ? "CLAUDE_CODE_OAUTH_TOKEN" : anthropicCredentialPath.toString();
            statuses.put(ProviderId.ANTHROPIC, new StartupRenderer.ProviderStatus(
                    authSource, providerModels, modelSource, checks.get(ProviderId.ANTHROPIC)));
        }
        EffectiveConfig displayConfig = effectiveDefaultProvider == effective.routing().defaultProvider()
                ? effective
                : new EffectiveConfig(effective.server(),
                new EffectiveConfig.Routing(effective.routing().provider(), effectiveDefaultProvider),
                effective.clientAuth(), effective.codex(), effective.anthropic(), effective.cors(),
                effective.logging(), effective.startup(), effective.sources());
        spec.commandLine().getOut().print(StartupRenderer.render(displayConfig, statuses));
        spec.commandLine().getOut().flush();
        if (doctorMode) {
            boolean failed = checks.values().stream().anyMatch(check -> check.state() == StartupRenderer.Check.State.FAILED);
            failed |= statuses.values().stream().anyMatch(status -> status.models().isEmpty()
                    || "fallback".equals(status.modelSource()));
            server.stop();
            apiKeyStore.stopWatching();
            if (anthropicStore != null) anthropicStore.close();
            authHttpClient.close();
            return failed ? 1 : 0;
        }
        setupShutdownHook(server, authHttpClient, apiKeyStore, anthropicStore);

        // Keep main thread alive
        Thread.currentThread().join();
        return 0;
    }

    Integer handleGenerateKey() {
        String key = ApiKeyUtils.generateNewKey();
        spec.commandLine().getOut().println(key);
        return 0;
    }

    ServerConfig buildServerConfig() throws Exception {
        ServeOptions options = activeOptions == null ? rootOptions : activeOptions;
        EffectiveConfig effective = EffectiveConfigLoader.load(options.configPath(), environment.get(), options.toOverrides());
        Map<String, String> keys = new HashMap<>(effective.clientAuth().environmentKeys());
        if (effective.clientAuth().keysFile() != null) {
            Files.readAllLines(effective.clientAuth().keysFile()).stream().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> ApiKeyUtils.parseKeyEntry(line, keys));
        }
        String admin = effective.clientAuth().environmentAdminKey();
        if (effective.clientAuth().adminKeyFile() != null) admin = Files.readString(effective.clientAuth().adminKeyFile()).strip();
        return effective.legacyServerConfig(keys, admin);
    }

    List<String> parseModelList() {
        try {
            List<String> modelList = EffectiveConfigLoader.load(null, environment.get(), rootOptions.toOverrides()).codex().models();
            return modelList.isEmpty() ? null : modelList;
        } catch (ConfigException error) {
            throw error;
        }
    }

    /** Returns only client keys supplied by the environment, not the key file. */
    Map<String, String> parseInlineKeys() {
        return new HashMap<>(EffectiveConfigLoader.load(null, environment.get(), rootOptions.toOverrides())
                .clientAuth().environmentKeys());
    }

    Map<String, String> parseApiKeyMap() throws Exception {
        EffectiveConfig effective = EffectiveConfigLoader.load(rootOptions.configPath(), environment.get(), rootOptions.toOverrides());
        Map<String, String> apiKeyMap = new HashMap<>(effective.clientAuth().environmentKeys());
        if (effective.clientAuth().keysFile() != null) Files.readAllLines(effective.clientAuth().keysFile()).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, apiKeyMap));
        return apiKeyMap;
    }

    boolean checkAuthFileExists(ServerConfig config) {
        String existingAuthFile = findExistingAuthFile(config.oauthFilePath());
        if (existingAuthFile == null) {
            List<String> candidates = AuthFileResolver.resolveCandidates(config.oauthFilePath());
            if (config.oauthFilePath() != null && !config.oauthFilePath().isEmpty()) {
                System.err.println("No auth file was found at " + config.oauthFilePath() + ".");
            } else {
                System.err.println("No auth file was found in the default search paths: "
                        + String.join(", ", candidates) + ".");
            }
            System.err.println("Run `codex login` and try again.");
            return false;
        }
        return true;
    }

    List<String> resolveAvailableModels(ModelResolver modelResolver) {
        try {
            return modelResolver.resolveModels();
        } catch (Exception e) {
            System.err.println("Warning: Could not discover models: " + e);
            return List.of();
        }
    }

    List<String> resolveAvailableModels(ModelCatalog modelCatalog) {
        try {
            return modelCatalog.resolveModels().stream()
                    .map(ProviderModel::id)
                    .toList();
        } catch (Exception error) {
            System.err.println("Warning: Could not discover models: " + error);
            return List.of();
        }
    }

    List<String> resolveAvailableModels(ModelCatalog modelCatalog, ProviderId provider) {
        try {
            return modelCatalog.resolveModels().stream()
                    .filter(model -> model.provider() == provider)
                    .map(ProviderModel::id)
                    .toList();
        } catch (Exception error) {
            System.err.println("Warning: Could not discover models for "
                    + provider.wireName() + ": " + error);
            return List.of();
        }
    }

    private AnthropicCompatibilityProfile anthropicProfile(EffectiveConfig effective) {
        AnthropicCompatibilityProfile source =
                AnthropicCompatibilityProfile.claudeCodeOAuth();
        String base = effective.anthropic().baseUrl();
        URI tokenUri = !"default".equalsIgnoreCase(effective.anthropic().tokenUrl())
                ? URI.create(effective.anthropic().tokenUrl())
                : source.tokenUri();
        return new AnthropicCompatibilityProfile(
                source.name(),
                source.clientId(),
                source.authorizationUri(),
                tokenUri,
                source.redirectUri(),
                URI.create(base + "/v1/messages?beta=true"),
                URI.create(base + "/v1/models?limit=100"),
                source.scopes(),
                source.anthropicVersion(),
                source.oauthBeta(),
                source.claudeCodeBeta(),
                source.oauthSystemPreamble()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record StartupProbeResult(boolean success, int statusCode, String message, String responseText, String model) {}

    StartupProbeResult verifyChatCompletionThroughProxy(
            ServerConfig config,
            List<String> availableModels,
            String apiKey,
            HttpClient httpClient
    ) {
        String model = selectStartupProbeModel(config, availableModels);
        String body = """
                {"model":"%s","messages":[{"role":"user","content":"Hello!"}],"stream":true}
                """.formatted(model);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(startupProbeUrl(config)))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            int status = response.statusCode();
            String responseText = status >= 200 && status < 300
                    ? extractStartupProbeResponseText(response.body())
                    : formatStartupProbeRawBody(response.body());
            if (status >= 200 && status < 300) {
                boolean hasModelResponse = hasActualStartupProbeResponse(responseText);
                return new StartupProbeResult(
                        hasModelResponse,
                        status,
                        hasModelResponse ? "HTTP " + status : "HTTP " + status + ", no model response text",
                        responseText,
                        model
                );
            }
            return new StartupProbeResult(false, status, "HTTP " + status, responseText, model);
        } catch (Exception e) {
            return new StartupProbeResult(false, 0, e.getClass().getSimpleName() + ": " + e.getMessage(), null, model);
        }
    }

    private static String extractStartupProbeResponseText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "<empty response body>";
        }
        if (looksLikeSse(responseBody)) {
            return extractStreamingStartupProbeResponseText(responseBody);
        }
        try {
            JsonNode root = Json.MAPPER.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return "<missing choices[0].message.content>";
            }
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice != null ? firstChoice.get("message") : null;
            JsonNode content = message != null ? message.get("content") : null;
            if (content == null) {
                return "<missing choices[0].message.content>";
            }
            if (content.isNull()) {
                return "<null choices[0].message.content>";
            }
            if (content.isTextual()) {
                return formatStartupProbeText(content.asText());
            }
            return formatStartupProbeText(Json.MAPPER.writeValueAsString(content));
        } catch (Exception e) {
            return "<unparseable response body: " + formatStartupProbeText(responseBody) + ">";
        }
    }

    private static String extractStreamingStartupProbeResponseText(String responseBody) {
        StringBuilder text = new StringBuilder();
        boolean sawNullContent = false;
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
            for (ServerSentEvent event : SseParser.parse(input)) {
                String data = event.data();
                if (data == null || data.isBlank()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                JsonNode root = Json.MAPPER.readTree(data);
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray()) {
                    continue;
                }
                for (JsonNode choice : choices) {
                    JsonNode delta = choice.get("delta");
                    JsonNode content = delta != null ? delta.get("content") : null;
                    if (content == null) {
                        continue;
                    }
                    if (content.isNull()) {
                        sawNullContent = true;
                    } else if (content.isTextual()) {
                        text.append(content.asText());
                    } else {
                        text.append(Json.MAPPER.writeValueAsString(content));
                    }
                }
            }
        } catch (Exception e) {
            return "<unparseable streaming response body: " + formatStartupProbeText(responseBody) + ">";
        }

        if (!text.isEmpty()) {
            return formatStartupProbeText(text.toString());
        }
        return sawNullContent
                ? "<null streaming choices[].delta.content>"
                : "<missing streaming choices[].delta.content>";
    }

    private static boolean looksLikeSse(String responseBody) {
        String trimmed = responseBody.stripLeading();
        return trimmed.startsWith("data:") || trimmed.startsWith("event:");
    }

    private static boolean hasActualStartupProbeResponse(String responseText) {
        return responseText != null && !responseText.isBlank() && !responseText.startsWith("<");
    }

    private static String formatStartupProbeRawBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "<empty response body>";
        }
        return formatStartupProbeText(responseBody);
    }

    private static String formatStartupProbeText(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String selectStartupProbeModel(ServerConfig config, List<String> availableModels) {
        if (availableModels != null && !availableModels.isEmpty()) {
            return availableModels.stream()
                    .filter(model -> model != null
                            && model.toLowerCase(Locale.ROOT).contains("claude")
                            && model.toLowerCase(Locale.ROOT).contains("sonnet"))
                    .findFirst()
                    .orElse(availableModels.getFirst());
        }
        if (config.models() != null && !config.models().isEmpty()) {
            return config.models().getFirst();
        }
        return ServerConfig.DEFAULT_MODEL;
    }

    private static String startupProbeUrl(ServerConfig config) {
        String host = clientHostForBindHost(config.host());
        return "http://" + hostForUri(host) + ":" + config.port() + "/v1/chat/completions";
    }

    private static String clientHostForBindHost(String host) {
        if (host == null || host.isBlank()) {
            return ServerConfig.DEFAULT_HOST;
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if ("0.0.0.0".equals(normalized) || "::".equals(normalized) || "0:0:0:0:0:0:0:0".equals(normalized)) {
            return ServerConfig.DEFAULT_HOST;
        }
        return host.strip();
    }

    private static String hostForUri(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static String firstConfiguredApiKey(ServerConfig config) {
        if (config.adminKey() != null) {
            return config.adminKey();
        }
        return config.apiKeys().keySet().stream().findFirst().orElse(null);
    }

    void setupShutdownHook(ProxyServer server, HttpClient authHttpClient, ApiKeyStore apiKeyStore) {
        setupShutdownHook(server, authHttpClient, apiKeyStore, null);
    }

    void setupShutdownHook(
            ProxyServer server,
            HttpClient authHttpClient,
            ApiKeyStore apiKeyStore,
            AutoCloseable anthropicStore
    ) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
            authHttpClient.close();
            apiKeyStore.stopWatching();
            if (anthropicStore != null) {
                try {
                    anthropicStore.close();
                } catch (Exception error) {
                    System.err.println(
                            "Warning: failed to close Anthropic credential store: "
                                    + error.getMessage());
                }
            }
        }, "shutdown-hook"));
    }

    static String findExistingAuthFile(String authFilePath) {
        for (String candidate : AuthFileResolver.resolveCandidates(authFilePath)) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    @Command(name = "serve", description = "Start the proxy server.", mixinStandardHelpOptions = true)
    static final class ServeCommand implements Callable<Integer> {
        @ParentCommand AIProxyOauth root;
        @Mixin ServeOptions options = new ServeOptions();

        @Override public Integer call() throws Exception {
            return root.runServe(options);
        }
    }

    StartupProbeResult verifyAnthropicThroughProxy(
            ServerConfig config,
            List<String> availableModels,
            String apiKey,
            HttpClient httpClient
    ) {
        String model = selectProviderModel(availableModels, "claude-sonnet-4-5");
        String body;
        try {
            body = Json.MAPPER.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", 1,
                    "messages", List.of(Map.of("role", "user", "content", "Reply OK"))));
        } catch (Exception error) {
            return new StartupProbeResult(false, 0, error.getMessage(), null, model);
        }
        String host = clientHostForBindHost(config.host());
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + hostForUri(host) + ":" + config.port() + "/v1/messages"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) request.header("x-api-key", apiKey);
        try {
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String responseText = formatStartupProbeRawBody(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = Json.MAPPER.readTree(response.body());
                String text = root.path("content").isArray() && !root.path("content").isEmpty()
                        ? root.path("content").get(0).path("text").asText("") : "";
                boolean success = !text.isBlank();
                return new StartupProbeResult(success, response.statusCode(),
                        success ? "HTTP " + response.statusCode() : "HTTP " + response.statusCode() + ", no model response text",
                        success ? formatStartupProbeText(text) : responseText, model);
            }
            return new StartupProbeResult(false, response.statusCode(), "HTTP " + response.statusCode(), responseText, model);
        } catch (Exception error) {
            return new StartupProbeResult(false, 0, error.getClass().getSimpleName() + ": " + error.getMessage(), null, model);
        }
    }

    private static StartupRenderer.Check check(StartupProbeResult probe) {
        return probe.success()
                ? StartupRenderer.Check.ok(probe.model())
                : StartupRenderer.Check.failed(probe.model(), probe.message() + (probe.responseText() == null ? "" : ": " + probe.responseText()));
    }

    private static String selectProviderModel(List<String> models, String fallback) {
        if (models == null || models.isEmpty()) return fallback;
        return models.stream().filter(model -> model.toLowerCase(Locale.ROOT).contains("sonnet"))
                .findFirst().orElse(models.getFirst());
    }

    private static String anthropicModelSource(AnthropicModelResolver resolver, EffectiveConfig config) {
        if (!config.anthropic().models().isEmpty()) return "configured";
        return switch (resolver.source()) {
            case DISCOVERED -> "discovered";
            case CACHE, LAST_GOOD -> "cache";
            default -> "fallback";
        };
    }

    private static String codexModelSource(ModelResolver resolver) {
        return switch (resolver.source()) {
            case CONFIGURED -> "configured";
            case CACHE -> "cache";
            case DISCOVERED -> "discovered";
            default -> "fallback";
        };
    }

    private static String startupClientKey(EffectiveConfig config) {
        if (config.clientAuth().environmentAdminKey() != null) return config.clientAuth().environmentAdminKey();
        if (!config.clientAuth().environmentKeys().isEmpty()) return config.clientAuth().environmentKeys().keySet().iterator().next();
        Path adminFile = config.clientAuth().adminKeyFile();
        try {
            if (adminFile != null) return Files.readString(adminFile).strip();
            if (config.clientAuth().keysFile() != null) {
                Map<String, String> parsed = new HashMap<>();
                Files.readAllLines(config.clientAuth().keysFile()).stream().map(String::strip)
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .forEach(line -> ApiKeyUtils.parseKeyEntry(line, parsed));
                return parsed.keySet().stream().findFirst().orElse(null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static final class ServeOptions {
        @Option(names = "--config", paramLabel = "<yaml>", description = "Load configuration from this YAML file.")
        String config;
        @Option(names = "--host", paramLabel = "<address>", description = "Host interface to bind to.")
        String host;
        @Option(names = "--port", paramLabel = "<port>", description = "Port to listen on.")
        Integer port;
        @Option(names = "--provider", paramLabel = "<auto|codex|anthropic|both>", description = "Upstream provider selection.")
        String provider;
        @Option(names = "--default-provider", paramLabel = "<codex|anthropic>", description = "Provider for unqualified OpenAI-compatible model names.")
        String defaultProvider;
        @Option(names = "--startup-check", paramLabel = "<off|credentials|inference>", description = "Startup verification mode.")
        String startupCheck;
        @Option(names = "--verbose", description = "List model IDs in startup output.")
        Boolean verbose;
        @Option(names = "--client-keys-file", paramLabel = "<path>", description = "File containing proxy client keys.")
        String clientKeysFile;
        @Option(names = "--admin-client-key-file", paramLabel = "<path>", description = "File containing the proxy admin client key.")
        String adminClientKeyFile;
        @Option(names = "--cors-origin", paramLabel = "<origin>", split = ",", description = "Allowed browser origin; repeatable.")
        List<String> corsOrigins;
        @Option(names = "--allow-any-cors", description = "Allow every browser origin (requires client authentication).")
        Boolean allowAnyCors;
        @Option(names = "--log-requests", description = "Store bounded request/response bodies and metadata with sensitive headers redacted.")
        Boolean logRequests;
        @Option(names = "--request-log-dir", paramLabel = "<path>", description = "Directory for protected request logs.")
        String requestLogDir;
        @Option(names = "--codex-models", paramLabel = "<ids>", description = "Comma-separated Codex model override.")
        String codexModels;
        @Option(names = "--codex-version", paramLabel = "<version>", description = "Codex version used for model discovery.")
        String codexVersion;
        @Option(names = "--codex-base-url", paramLabel = "<url>", description = "Codex upstream base URL.")
        String codexBaseUrl;
        @Option(names = "--codex-oauth-file", paramLabel = "<path>", description = "Codex OAuth credential file.")
        String codexOauthFile;
        @Option(names = "--codex-oauth-client-id", paramLabel = "<id>", description = "Codex OAuth client ID.")
        String codexOauthClientId;
        @Option(names = "--codex-oauth-token-url", paramLabel = "<url>", description = "Codex OAuth token URL.")
        String codexOauthTokenUrl;
        @Option(names = "--codex-store", description = "Ask Codex upstream to store responses.")
        Boolean codexStore;
        @Option(names = "--codex-forward-prompt-cache-headers", description = "Forward Codex prompt cache headers.")
        Boolean codexForwardPromptCacheHeaders;
        @Option(names = "--codex-instructions-mode", paramLabel = "<none|file|latest>", description = "Codex instructions source.")
        String codexInstructionsMode;
        @Option(names = "--codex-instructions-file", paramLabel = "<path>", description = "Instructions file required by file mode.")
        String codexInstructionsFile;
        @Option(names = "--codex-instructions-cache-dir", paramLabel = "<path>", description = "Cache for latest Codex instructions.")
        String codexInstructionsCacheDir;
        @Option(names = "--anthropic-models", paramLabel = "<ids>", description = "Comma-separated Anthropic model override.")
        String anthropicModels;
        @Option(names = "--anthropic-base-url", paramLabel = "<url>", description = "Anthropic upstream base URL, with or without /v1.")
        String anthropicBaseUrl;
        @Option(names = "--anthropic-oauth-file", paramLabel = "<path>", description = "Anthropic OAuth credential file.")
        String anthropicOauthFile;
        @Option(names = "--anthropic-token-url", paramLabel = "<url>", description = "Anthropic OAuth token URL or default.")
        String anthropicTokenUrl;

        Path configPath() { return config == null ? null : Path.of(config); }

        ConfigOverrides toOverrides() {
            ConfigOverrides value = new ConfigOverrides();
            value.host = host; value.port = port; value.provider = provider; value.defaultProvider = defaultProvider;
            value.startupCheck = startupCheck; value.verbose = verbose; value.clientKeysFile = clientKeysFile;
            value.adminClientKeyFile = adminClientKeyFile; value.corsOrigins = corsOrigins;
            value.allowAnyCors = allowAnyCors; value.logRequests = logRequests; value.requestLogDir = requestLogDir;
            value.codexModels = codexModels; value.codexVersion = codexVersion; value.codexBaseUrl = codexBaseUrl;
            value.codexOauthFile = codexOauthFile; value.codexOauthClientId = codexOauthClientId;
            value.codexOauthTokenUrl = codexOauthTokenUrl; value.codexStore = codexStore;
            value.codexForwardPromptCacheHeaders = codexForwardPromptCacheHeaders;
            value.codexInstructionsMode = codexInstructionsMode; value.codexInstructionsFile = codexInstructionsFile;
            value.codexInstructionsCacheDir = codexInstructionsCacheDir; value.anthropicModels = anthropicModels;
            value.anthropicBaseUrl = anthropicBaseUrl; value.anthropicOauthFile = anthropicOauthFile;
            value.anthropicTokenUrl = anthropicTokenUrl;
            return value;
        }
    }

    @Command(name = "auth", description = "Manage and inspect provider credentials.",
            subcommands = {AnthropicAuthCommand.class, AuthStatusCommand.class})
    static final class AuthCommand implements Runnable { public void run() {} }

    @Command(name = "anthropic", description = "Manage Anthropic OAuth credentials.",
            subcommands = {AnthropicLoginCommand.class, AnthropicLogoutCommand.class})
    static final class AnthropicAuthCommand implements Runnable { public void run() {} }

    @Command(name = "login", description = "Run interactive Anthropic OAuth login.", mixinStandardHelpOptions = true)
    static final class AnthropicLoginCommand implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Option(names = "--anthropic-oauth-file") String oauthFile;
        @Option(names = "--allow-stdin-oauth-code") boolean allowStdin;
        public Integer call() {
            Path path = oauthFile == null ? AnthropicCredentialPaths.defaultPath() : Path.of(oauthFile).toAbsolutePath().normalize();
            return AnthropicAuthCommands.system(path, spec.commandLine().getOut(), spec.commandLine().getErr()).login(allowStdin);
        }
    }

    @Command(name = "logout", description = "Delete the resolved Anthropic OAuth credential.", mixinStandardHelpOptions = true)
    static final class AnthropicLogoutCommand implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Option(names = "--anthropic-oauth-file") String oauthFile;
        @Option(names = "--yes") boolean yes;
        public Integer call() {
            Path path = oauthFile == null ? AnthropicCredentialPaths.defaultPath() : Path.of(oauthFile).toAbsolutePath().normalize();
            return AnthropicAuthCommands.system(path, spec.commandLine().getOut(), spec.commandLine().getErr()).logout(yes);
        }
    }

    @Command(name = "status", description = "Show provider credential availability.", mixinStandardHelpOptions = true)
    static final class AuthStatusCommand implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Option(names = "--config") Path config;
        public Integer call() {
            AIProxyOauth root = (AIProxyOauth) spec.root().userObject();
            EffectiveConfig effective = EffectiveConfigLoader.load(config, root.environment.get(), new ConfigOverrides());
            String codex = findExistingAuthFile(effective.codex().oauthFile() == null ? null : effective.codex().oauthFile().toString());
            boolean anthropic = root.environment.get().containsKey("CLAUDE_CODE_OAUTH_TOKEN")
                    || Files.isRegularFile(effective.anthropic().oauthFile());
            spec.commandLine().getOut().println("Codex: " + (codex == null ? "not found" : "available from " + codex));
            spec.commandLine().getOut().println("Anthropic: " + (anthropic ? "available" : "not found"));
            return codex != null || anthropic ? 0 : 1;
        }
    }

    @Command(name = "key", description = "Manage proxy client keys.", subcommands = KeyGenerateCommand.class)
    static final class KeyCommand implements Runnable { public void run() {} }

    @Command(name = "generate", description = "Generate a proxy client key.", mixinStandardHelpOptions = true)
    static final class KeyGenerateCommand implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Parameters(index = "0", arity = "0..1", paramLabel = "name") String name;
        public Integer call() {
            String key = ApiKeyUtils.generateNewKey();
            spec.commandLine().getOut().println(name == null ? key : name + ":" + key);
            return 0;
        }
    }

    @Command(name = "config", description = "Inspect configuration.", subcommands = ConfigShowCommand.class)
    static final class ConfigCommand implements Runnable { public void run() {} }

    @Command(name = "show", description = "Print resolved, redacted configuration and value sources.", mixinStandardHelpOptions = true)
    static final class ConfigShowCommand implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Option(names = "--config") Path config;
        public Integer call() {
            AIProxyOauth root = (AIProxyOauth) spec.root().userObject();
            try {
                EffectiveConfig effective = EffectiveConfigLoader.load(config, root.environment.get(), new ConfigOverrides());
                printResolvedConfig(spec.commandLine().getOut(), effective);
                return 0;
            } catch (ConfigException error) {
                spec.commandLine().getErr().println("Configuration error: " + error.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "doctor", description = "Validate configuration, credentials, URLs, and model discovery.", mixinStandardHelpOptions = true)
    static final class DoctorCommand implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Option(names = "--config") Path config;
        @Option(names = "--inference", description = "Also require usable credentials for provider inference checks.") boolean inference;
        public Integer call() throws Exception {
            AIProxyOauth root = (AIProxyOauth) spec.root().userObject();
            ServeOptions options = new ServeOptions();
            options.config = config == null ? null : config.toString();
            options.startupCheck = inference ? "inference" : "credentials";
            return root.runProxy(options, true);
        }
    }

    private static void printResolvedConfig(java.io.PrintWriter out, EffectiveConfig config) {
        out.println("server.host: " + config.server().host() + source(config, "server.host"));
        out.println("server.port: " + config.server().port() + source(config, "server.port"));
        out.println("routing.provider: " + config.routing().provider().name().toLowerCase(Locale.ROOT) + source(config, "routing.provider"));
        out.println("routing.default_provider: " + config.routing().defaultProvider().wireName() + source(config, "routing.default_provider"));
        out.println("client_auth.keys_file: " + displayPath(config.clientAuth().keysFile()) + source(config, "client_auth.keys_file"));
        out.println("client_auth.admin_key_file: " + displayPath(config.clientAuth().adminKeyFile()) + source(config, "client_auth.admin_key_file"));
        out.println("client_auth.environment_keys: " + (config.clientAuth().environmentKeys().isEmpty() ? "not set" : "<redacted>"));
        out.println("client_auth.environment_admin_key: " + (config.clientAuth().environmentAdminKey() == null ? "not set" : "<redacted>"));
        out.println("codex.models: " + config.codex().models() + source(config, "codex.models"));
        out.println("codex.base_url: " + config.codex().baseUrl() + source(config, "codex.base_url"));
        out.println("codex.oauth_file: " + displayPath(config.codex().oauthFile()) + source(config, "codex.oauth_file"));
        out.println("codex.oauth_client_id: " + config.codex().oauthClientId() + source(config, "codex.oauth_client_id"));
        out.println("codex.oauth_token_url: " + config.codex().oauthTokenUrl() + source(config, "codex.oauth_token_url"));
        out.println("anthropic.models: " + config.anthropic().models() + source(config, "anthropic.models"));
        out.println("anthropic.base_url: " + config.anthropic().baseUrl() + source(config, "anthropic.base_url"));
        out.println("anthropic.oauth_file: " + displayPath(config.anthropic().oauthFile()) + source(config, "anthropic.oauth_file"));
        out.println("cors.origins: " + config.cors().origins() + source(config, "cors.origins"));
        out.println("cors.allow_any: " + config.cors().allowAny() + source(config, "cors.allow_any"));
        out.println("logging.requests: " + config.logging().requests() + source(config, "logging.requests"));
        out.println("logging.directory: " + config.logging().directory() + source(config, "logging.directory"));
        out.println("startup.check: " + config.startup().check().name().toLowerCase(Locale.ROOT) + source(config, "startup.check"));
        out.flush();
    }

    private static String source(EffectiveConfig config, String key) {
        return "  # source: " + config.sources().getOrDefault(key, "default");
    }

    private static String displayPath(Path path) { return path == null ? "null" : path.toString(); }

    public static CommandLine commandLine() { return commandLine(new AIProxyOauth()); }

    static CommandLine commandLine(AIProxyOauth root) {
        CommandLine command = new CommandLine(root);
        command.setParameterExceptionHandler((error, args) -> {
            String message = error.getMessage();
            Map<String, String> replacements = Map.ofEntries(
                    Map.entry("--models", "--codex-models"), Map.entry("--base-url", "--codex-base-url"),
                    Map.entry("--oauth-file", "--codex-oauth-file"), Map.entry("--oauth-client-id", "--codex-oauth-client-id"),
                    Map.entry("--oauth-token-url", "--codex-oauth-token-url"), Map.entry("--api-keys-file", "--client-keys-file"),
                    Map.entry("--providers", "--provider"), Map.entry("--store", "--codex-store"),
                    Map.entry("--forward-prompt-cache-headers", "--codex-forward-prompt-cache-headers"),
                    Map.entry("--codex-instructions", "--codex-instructions-mode"),
                    Map.entry("--generate-key", "key generate"), Map.entry("--anthropic-login", "auth anthropic login"),
                    Map.entry("--anthropic-logout", "auth anthropic logout"), Map.entry("--api-key", "AIPROXY_CLIENT_KEYS"),
                    Map.entry("--admin-key", "AIPROXY_ADMIN_CLIENT_KEY"));
            for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                if (message != null && message.contains(replacement.getKey())) {
                    message += System.lineSeparator() + "Use " + replacement.getValue() + " instead of " + replacement.getKey() + ".";
                    break;
                }
            }
            error.getCommandLine().getErr().println(message);
            return 2;
        });
        return command;
    }

    public static void main(String[] args) {
        int exitCode = commandLine().execute(args);
        System.exit(exitCode);
    }
}
