package com.aiproxyoauth;

import com.aiproxyoauth.auth.AuthFileResolver;
import com.aiproxyoauth.auth.AuthManager;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.server.ApiKeyStore;
import com.aiproxyoauth.server.ProxyServer;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.ApiKeyUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "AIProxyOauth",
        description = "Local HTTP proxy server exposing OpenAI-compatible endpoints via ChatGPT OAuth tokens.",
        mixinStandardHelpOptions = true,
        version = "AIProxyOauth 1.0.0"
)
public class AIProxyOauth implements Callable<Integer> {

    @Option(names = "--host", description = "Host interface to bind to. Default: 127.0.0.1")
    private String host;

    @Option(names = "--port", description = "Port to listen on. Default: 10531")
    private Integer port;

    @Option(names = "--models", description = "Comma-separated model ids to expose from /v1/models.")
    private String models;

    @Option(names = "--codex-version", description = "Codex API version to use for model discovery.")
    private String codexVersion;

    @Option(names = "--base-url", description = "Override the upstream Codex base URL.")
    private String baseUrl;

    @Option(names = "--oauth-client-id", description = "Override the OAuth client id used for refresh.")
    private String oauthClientId;

    @Option(names = "--oauth-token-url", description = "Override the OAuth token URL used for refresh.")
    private String oauthTokenUrl;

    @Option(names = "--oauth-file", description = "Path to the local auth.json file.")
    private String oauthFile;

    @Option(names = "--store", description = "Whether to store responses on the server. Default: false")
    private boolean store;

    @Option(names = "--api-key", description = "Comma-separated API keys clients must present.")
    private String apiKey;

    @Option(names = "--api-keys-file", description = "Path to file with one API key per line.")
    private String apiKeysFile;

    @Option(names = "--generate-key", arity = "0..1", fallbackValue = "",
            description = "Print a new random API key and exit. Optionally provide a name: --generate-key myapp")
    private String generateKey;

    @Option(names = "--admin-key", description = "Owner key that can see all users' stats at GET /v1/usage.")
    private String adminKey;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        if (generateKey != null) {
            return handleGenerateKey();
        }

        ServerConfig config = buildServerConfig();

        Map<String, String> inlineKeys = parseInlineKeys();
        if (config.adminKey() != null) inlineKeys.remove(config.adminKey());
        String explicitAdminKey = (adminKey != null && !adminKey.isBlank()) ? adminKey.strip() : null;
        ApiKeyStore apiKeyStore = new ApiKeyStore(inlineKeys, apiKeysFile, explicitAdminKey);
        if (apiKeysFile != null && !apiKeysFile.isBlank()) {
            apiKeyStore.reload();
        }
        apiKeyStore.startWatching();

        if (!checkAuthFileExists(config)) {
            return 1;
        }

        HttpClient authHttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        AuthManager authManager = new AuthManager(config, authHttpClient);

        // Initial auth load to verify credentials
        authManager.ensureFresh();

        CodexHttpClient httpClient = new CodexHttpClient(config, authManager);
        ModelResolver modelResolver = new ModelResolver(httpClient, config.models(), config.codexVersion());

        // Discover models upfront
        List<String> availableModels = resolveAvailableModels(modelResolver);

        // Start server
        UsageTracker usageTracker = new UsageTracker();
        ProxyServer server = new ProxyServer(config, httpClient, modelResolver, usageTracker, apiKeyStore);
        server.start();

        printStartupBanner(config, availableModels);
        setupShutdownHook(server, authHttpClient, apiKeyStore);

        // Keep main thread alive
        Thread.currentThread().join();
        return 0;
    }

    Integer handleGenerateKey() {
        String key = ApiKeyUtils.generateNewKey();
        spec.commandLine().getOut().println(generateKey.isEmpty() ? key : generateKey + ":" + key);
        return 0;
    }

    ServerConfig buildServerConfig() throws Exception {
        Map<String, String> apiKeyMap = parseApiKeyMap();
        String resolvedAdminKey = (adminKey != null && !adminKey.isBlank()) ? adminKey.strip() : null;

        // If no CLI admin key, look for an entry named "admin" in the keys map
        if (resolvedAdminKey == null) {
            String foundKey = null;
            for (Map.Entry<String, String> entry : apiKeyMap.entrySet()) {
                if ("admin".equalsIgnoreCase(entry.getValue())) {
                    foundKey = entry.getKey();
                    break;
                }
            }
            if (foundKey != null) {
                resolvedAdminKey = foundKey;
                apiKeyMap.remove(foundKey); // Remove from regular keys
            }
        }

        return new ServerConfig(
                host != null ? host : ServerConfig.DEFAULT_HOST,
                port != null ? port : ServerConfig.DEFAULT_PORT,
                parseModelList(),
                codexVersion,
                baseUrl != null ? baseUrl : ServerConfig.DEFAULT_BASE_URL,
                oauthClientId,
                oauthTokenUrl,
                oauthFile,
                ServerConfig.DEFAULT_INSTRUCTIONS,
                store,
                apiKeyMap,
                resolvedAdminKey
        );
    }

    List<String> parseModelList() {
        if (models == null || models.isEmpty()) {
            return null;
        }
        List<String> modelList = Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return modelList.isEmpty() ? null : modelList;
    }

    /** Returns only the keys from --api-key (not --api-keys-file). */
    Map<String, String> parseInlineKeys() {
        Map<String, String> map = new HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            Arrays.stream(apiKey.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, map));
        }
        return map;
    }

    Map<String, String> parseApiKeyMap() throws Exception {
        Map<String, String> apiKeyMap = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            Arrays.stream(apiKey.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, apiKeyMap));
        }
        if (apiKeysFile != null && !apiKeysFile.isEmpty()) {
            Files.readAllLines(Path.of(apiKeysFile)).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, apiKeyMap));
        }
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

    void printStartupBanner(ServerConfig config, List<String> availableModels) {
        java.io.PrintWriter out = spec.commandLine().getOut();
        String url = "http://" + config.host() + ":" + config.port() + "/v1";
        out.println();
        out.println("OpenAI OAuth Proxy Server started");
        out.println("  Endpoint: " + url);
        if (!availableModels.isEmpty()) {
            out.println("  Models:   " + String.join(", ", availableModels));
        }
        if (!config.apiKeys().isEmpty()) {
            String names = String.join(", ", config.apiKeys().values());
            out.println("  Keys:     " + config.apiKeys().size() + " key(s) configured (" + names + ")");
        }
        if (config.adminKey() != null) {
            out.println("  Admin:    key configured");
        }
        out.println();
    }

    void setupShutdownHook(ProxyServer server, HttpClient authHttpClient, ApiKeyStore apiKeyStore) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
            authHttpClient.close();
            apiKeyStore.stopWatching();
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AIProxyOauth()).execute(args);
        System.exit(exitCode);
    }
}
