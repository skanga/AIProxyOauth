package com.aiproxyoauth.config;

import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicCredentialPaths;
import com.aiproxyoauth.util.ApiKeyUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads and validates the one effective configuration using CLI > environment > YAML > defaults. */
public final class EffectiveConfigLoader {
    private static final String DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com";
    private static final String DEFAULT_ANTHROPIC_TOKEN = "default";
    private static final Map<String, Set<String>> YAML_KEYS = Map.ofEntries(
            Map.entry("server", Set.of("host", "port")),
            Map.entry("routing", Set.of("provider", "default_provider")),
            Map.entry("client_auth", Set.of("keys_file", "admin_key_file")),
            Map.entry("codex", Set.of("oauth_file", "models", "version", "base_url", "oauth_client_id",
                    "oauth_token_url", "store", "forward_prompt_cache_headers", "instructions")),
            Map.entry("codex.instructions", Set.of("mode", "file", "cache_dir")),
            Map.entry("anthropic", Set.of("oauth_file", "models", "base_url", "token_url")),
            Map.entry("cors", Set.of("origins", "allow_any")),
            Map.entry("logging", Set.of("requests", "directory")),
            Map.entry("startup", Set.of("check"))
    );

    private EffectiveConfigLoader() {}

    public static EffectiveConfig load(Path yamlFile, Map<String, String> environment, ConfigOverrides cli) {
        Map<String, String> yaml = readYaml(yamlFile);
        Path yamlBase = yamlFile == null ? null : yamlFile.toAbsolutePath().normalize().getParent();
        Map<String, String> sources = new LinkedHashMap<>();

        String host = choose("server.host", cli.host, environment.get("AIPROXY_HOST"), yaml, "127.0.0.1", sources);
        int port = integer("server.port", cli.port, environment.get("AIPROXY_PORT"), yaml, 10531, sources);
        if (port < 1 || port > 65535) throw new ConfigException("server.port must be in range 1-65535");

        EffectiveConfig.ProviderSelection provider = enumValue("routing.provider", cli.provider,
                environment.get("AIPROXY_PROVIDER"), yaml, "auto", EffectiveConfig.ProviderSelection.class, sources);
        ProviderId defaultProvider = providerId(choose("routing.default_provider", cli.defaultProvider,
                environment.get("AIPROXY_DEFAULT_PROVIDER"), yaml, "codex", sources));
        if ("default".equals(sources.get("routing.default_provider"))
                && provider == EffectiveConfig.ProviderSelection.ANTHROPIC) {
            defaultProvider = ProviderId.ANTHROPIC;
        }
        validateDefaultProvider(provider, defaultProvider);

        Path keysFile = path("client_auth.keys_file", cli.clientKeysFile,
                environment.get("AIPROXY_CLIENT_KEYS_FILE"), yaml, null, yamlBase, sources);
        Path adminFile = path("client_auth.admin_key_file", cli.adminClientKeyFile,
                environment.get("AIPROXY_ADMIN_CLIENT_KEY_FILE"), yaml, null, yamlBase, sources);
        requireReadable(keysFile, "client_auth.keys_file");
        requireReadable(adminFile, "client_auth.admin_key_file");
        Map<String, String> environmentKeys = parseKeys(environment.get("AIPROXY_CLIENT_KEYS"));
        String environmentAdmin = stripToNull(environment.get("AIPROXY_ADMIN_CLIENT_KEY"));

        List<String> codexModels = list("codex.models", cli.codexModels,
                environment.get("AIPROXY_CODEX_MODELS"), yaml, sources);
        String codexVersion = nullable("codex.version", cli.codexVersion,
                environment.get("AIPROXY_CODEX_VERSION"), yaml, sources);
        String codexBase = normalizedUrl("codex.base_url", cli.codexBaseUrl,
                environment.get("AIPROXY_CODEX_BASE_URL"), yaml, ServerConfig.DEFAULT_BASE_URL, false, sources);
        Path codexOauth = path("codex.oauth_file", cli.codexOauthFile,
                environment.get("AIPROXY_CODEX_OAUTH_FILE"), yaml, null, yamlBase, sources);
        String codexClientId = choose("codex.oauth_client_id", cli.codexOauthClientId,
                environment.get("AIPROXY_CODEX_OAUTH_CLIENT_ID"), yaml, ServerConfig.DEFAULT_CLIENT_ID, sources);
        String codexTokenUrl = nullableUrl("codex.oauth_token_url", cli.codexOauthTokenUrl,
                environment.get("AIPROXY_CODEX_OAUTH_TOKEN_URL"), yaml, null, sources);
        boolean codexStore = bool("codex.store", cli.codexStore, environment.get("AIPROXY_CODEX_STORE"), yaml, false, sources);
        boolean forwardCache = bool("codex.forward_prompt_cache_headers", cli.codexForwardPromptCacheHeaders,
                environment.get("AIPROXY_CODEX_FORWARD_PROMPT_CACHE_HEADERS"), yaml, false, sources);
        EffectiveConfig.InstructionsMode instructionsMode = enumValue("codex.instructions.mode",
                cli.codexInstructionsMode, environment.get("AIPROXY_CODEX_INSTRUCTIONS_MODE"), yaml, "none",
                EffectiveConfig.InstructionsMode.class, sources);
        Path instructionsFile = path("codex.instructions.file", cli.codexInstructionsFile,
                environment.get("AIPROXY_CODEX_INSTRUCTIONS_FILE"), yaml, null, yamlBase, sources);
        Path instructionsCache = path("codex.instructions.cache_dir", cli.codexInstructionsCacheDir,
                environment.get("AIPROXY_CODEX_INSTRUCTIONS_CACHE_DIR"), yaml,
                Path.of("cache", "codex-instructions").toString(), yamlBase, sources);
        if (instructionsMode == EffectiveConfig.InstructionsMode.FILE) {
            if (instructionsFile == null) throw new ConfigException("codex.instructions.file is required when mode is file");
            requireReadable(instructionsFile, "codex.instructions.file");
        } else if (instructionsFile != null) {
            throw new ConfigException("codex.instructions.file conflicts with mode " + instructionsMode.name().toLowerCase(Locale.ROOT));
        }

        List<String> anthropicModels = list("anthropic.models", cli.anthropicModels,
                environment.get("AIPROXY_ANTHROPIC_MODELS"), yaml, sources);
        String anthropicBase = normalizedUrl("anthropic.base_url", cli.anthropicBaseUrl,
                environment.get("AIPROXY_ANTHROPIC_BASE_URL"), yaml, DEFAULT_ANTHROPIC_BASE, true, sources);
        Path anthropicOauth = path("anthropic.oauth_file", cli.anthropicOauthFile,
                environment.get("AIPROXY_ANTHROPIC_OAUTH_FILE"), yaml,
                AnthropicCredentialPaths.defaultPath().toString(), yamlBase, sources);
        String anthropicToken = choose("anthropic.token_url", cli.anthropicTokenUrl,
                environment.get("AIPROXY_ANTHROPIC_TOKEN_URL"), yaml, DEFAULT_ANTHROPIC_TOKEN, sources);
        if (!DEFAULT_ANTHROPIC_TOKEN.equalsIgnoreCase(anthropicToken)) {
            anthropicToken = stripTrailingSlash(validateUrl("anthropic.token_url", anthropicToken, false));
        }

        List<String> origins = listFromCli("cors.origins", cli.corsOrigins,
                environment.get("AIPROXY_CORS_ORIGINS"), yaml, sources);
        origins.forEach(EffectiveConfigLoader::validateOrigin);
        boolean allowAny = bool("cors.allow_any", cli.allowAnyCors, environment.get("AIPROXY_ALLOW_ANY_CORS"), yaml, false, sources);
        boolean authEnabled = keysFile != null || adminFile != null || !environmentKeys.isEmpty() || environmentAdmin != null;
        if (allowAny && !authEnabled) {
            throw new ConfigException("cors.allow_any requires proxy client authentication");
        }

        boolean logRequests = bool("logging.requests", cli.logRequests,
                environment.get("AIPROXY_LOG_REQUESTS"), yaml, false, sources);
        Path logDirectory = path("logging.directory", cli.requestLogDir,
                environment.get("AIPROXY_REQUEST_LOG_DIR"), yaml, Path.of("logs", "requests").toString(), yamlBase, sources);
        EffectiveConfig.StartupCheck startupCheck = enumValue("startup.check", cli.startupCheck,
                environment.get("AIPROXY_STARTUP_CHECK"), yaml, "inference", EffectiveConfig.StartupCheck.class, sources);
        boolean verbose = cli.verbose != null ? cli.verbose : parseBoolean("verbose", environment.get("AIPROXY_VERBOSE"), false);
        sources.put("startup.verbose", cli.verbose != null ? "cli" : environment.containsKey("AIPROXY_VERBOSE") ? "environment" : "default");

        return new EffectiveConfig(
                new EffectiveConfig.Server(host, port),
                new EffectiveConfig.Routing(provider, defaultProvider),
                new EffectiveConfig.ClientAuth(keysFile, adminFile, environmentKeys, environmentAdmin),
                new EffectiveConfig.Codex(codexModels, codexVersion, codexBase, codexOauth, codexClientId,
                        codexTokenUrl, codexStore, forwardCache, instructionsMode, instructionsFile, instructionsCache),
                new EffectiveConfig.Anthropic(anthropicModels, anthropicBase, anthropicOauth, anthropicToken),
                new EffectiveConfig.Cors(origins, allowAny),
                new EffectiveConfig.Logging(logRequests, logDirectory),
                new EffectiveConfig.Startup(startupCheck, verbose),
                Map.copyOf(sources));
    }

    private static Map<String, String> readYaml(Path file) {
        if (file == null) return Map.of();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new ConfigException("Configuration file is not readable: " + file);
        }
        try {
            JsonNode root = new YAMLMapper().readTree(file.toFile());
            if (root == null) return Map.of();
            if (!root.isObject()) throw new ConfigException("YAML root must be an object");
            Map<String, String> flat = new LinkedHashMap<>();
            flattenObject(root, "", flat);
            return flat;
        } catch (IOException error) {
            throw new ConfigException("Could not parse YAML configuration: " + error.getMessage(), error);
        }
    }

    private static void flattenObject(JsonNode object, String prefix, Map<String, String> flat) {
        Set<String> allowed = YAML_KEYS.get(prefix);
        if (allowed == null && !prefix.isEmpty()) throw new ConfigException("Unknown YAML section: " + prefix);
        object.properties().forEach(entry -> {
            String key = entry.getKey();
            if (prefix.isEmpty()) {
                if (!YAML_KEYS.containsKey(key)) throw new ConfigException("Unknown YAML key: " + key);
            } else if (!allowed.contains(key)) {
                if ("client_auth".equals(prefix) && ("keys".equals(key) || "admin_key".equals(key))) {
                    throw new ConfigException("inline client authentication secrets are prohibited; use files or environment variables");
                }
                throw new ConfigException("Unknown YAML key: " + prefix + "." + key);
            }
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            JsonNode value = entry.getValue();
            if (value.isObject()) flattenObject(value, full, flat);
            else if (value.isArray()) {
                List<String> values = new ArrayList<>();
                value.forEach(item -> {
                    if (!item.isValueNode()) throw new ConfigException("YAML list must contain scalar values: " + full);
                    values.add(item.asText());
                });
                flat.put(full, String.join(",", values));
            } else if (!value.isNull()) {
                // YAML 1.1 parsers commonly treat the plain scalar `off` as boolean false.
                flat.put(full, "startup.check".equals(full) && value.isBoolean() && !value.asBoolean()
                        ? "off" : value.asText());
            }
        });
    }

    private static String choose(String key, String cli, String env, Map<String, String> yaml,
                                 String defaultValue, Map<String, String> sources) {
        if (stripToNull(cli) != null) { sources.put(key, "cli"); return cli.strip(); }
        if (stripToNull(env) != null) { sources.put(key, "environment"); return env.strip(); }
        if (stripToNull(yaml.get(key)) != null) { sources.put(key, "yaml"); return yaml.get(key).strip(); }
        sources.put(key, "default"); return defaultValue;
    }

    private static String nullable(String key, String cli, String env, Map<String, String> yaml,
                                   Map<String, String> sources) {
        return choose(key, cli, env, yaml, null, sources);
    }

    private static int integer(String key, Integer cli, String env, Map<String, String> yaml,
                               int fallback, Map<String, String> sources) {
        if (cli != null) { sources.put(key, "cli"); return cli; }
        String value = choose(key, null, env, yaml, String.valueOf(fallback), sources);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new ConfigException(key + " must be an integer, got: " + value); }
    }

    private static boolean bool(String key, Boolean cli, String env, Map<String, String> yaml,
                                boolean fallback, Map<String, String> sources) {
        if (cli != null) { sources.put(key, "cli"); return cli; }
        return parseBoolean(key, choose(key, null, env, yaml, String.valueOf(fallback), sources), fallback);
    }

    private static boolean parseBoolean(String key, String value, boolean fallback) {
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new ConfigException(key + " must be true or false, got: " + value);
    }

    private static <E extends Enum<E>> E enumValue(String key, String cli, String env, Map<String, String> yaml,
                                                    String fallback, Class<E> type, Map<String, String> sources) {
        String value = choose(key, cli, env, yaml, fallback, sources);
        try { return Enum.valueOf(type, value.replace('-', '_').toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new ConfigException("Invalid " + key + ": " + value); }
    }

    private static List<String> list(String key, String cli, String env, Map<String, String> yaml,
                                     Map<String, String> sources) {
        String value = choose(key, cli, env, yaml, "", sources);
        return split(value);
    }

    private static List<String> listFromCli(String key, List<String> cli, String env, Map<String, String> yaml,
                                            Map<String, String> sources) {
        if (cli != null) {
            sources.put(key, "cli");
            return cli.stream().flatMap(value -> split(value).stream()).distinct().toList();
        }
        return list(key, null, env, yaml, sources);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).distinct().toList();
    }

    private static Path path(String key, String cli, String env, Map<String, String> yaml, String fallback,
                             Path yamlBase, Map<String, String> sources) {
        String value = choose(key, cli, env, yaml, fallback, sources);
        if (value == null || value.isBlank()) return null;
        Path parsed = expandHome(value);
        if (!parsed.isAbsolute()) {
            Path base = "yaml".equals(sources.get(key)) && yamlBase != null ? yamlBase : Path.of("").toAbsolutePath();
            parsed = base.resolve(parsed);
        }
        return parsed.normalize();
    }

    private static Path expandHome(String value) {
        if ("~".equals(value)) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value);
    }

    private static String normalizedUrl(String key, String cli, String env, Map<String, String> yaml,
                                        String fallback, boolean stripV1, Map<String, String> sources) {
        String value = validateUrl(key, choose(key, cli, env, yaml, fallback, sources), false);
        value = stripTrailingSlash(value);
        if (stripV1 && value.toLowerCase(Locale.ROOT).endsWith("/v1")) value = value.substring(0, value.length() - 3);
        return stripTrailingSlash(value);
    }

    private static String nullableUrl(String key, String cli, String env, Map<String, String> yaml,
                                      String fallback, Map<String, String> sources) {
        String value = choose(key, cli, env, yaml, fallback, sources);
        return value == null ? null : stripTrailingSlash(validateUrl(key, value, false));
    }

    private static String validateUrl(String key, String value, boolean origin) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null) throw new IllegalArgumentException();
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (!https && !(http && (origin || isLoopback(uri.getHost())))) {
                throw new ConfigException(key + " must use HTTPS (HTTP is allowed only for loopback development URLs)");
            }
            if (uri.getUserInfo() != null || uri.getFragment() != null || (origin && uri.getQuery() != null)) {
                throw new ConfigException("Invalid " + key + ": " + value);
            }
            return uri.toString();
        } catch (IllegalArgumentException error) {
            if (error instanceof ConfigException configError) throw configError;
            throw new ConfigException("Invalid " + key + ": " + value);
        }
    }

    private static void validateOrigin(String value) {
        String normalized = validateUrl("cors.origins", value, true);
        URI uri = URI.create(normalized);
        if ((uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) || uri.getQuery() != null) {
            throw new ConfigException("CORS origin must not contain a path, query, or fragment: " + value);
        }
    }

    private static boolean isLoopback(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value) || "::1".equals(value) || value.startsWith("127.")
                || "0:0:0:0:0:0:0:1".equals(value);
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }

    private static void requireReadable(Path file, String key) {
        if (file != null && (!Files.isRegularFile(file) || !Files.isReadable(file))) {
            throw new ConfigException(key + " is not a readable file: " + file);
        }
    }

    private static Map<String, String> parseKeys(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String, String> keys = new LinkedHashMap<>();
        split(value).forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, keys));
        return keys;
    }

    private static ProviderId providerId(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "codex" -> ProviderId.CODEX;
            case "anthropic" -> ProviderId.ANTHROPIC;
            default -> throw new ConfigException("Invalid routing.default_provider: " + value);
        };
    }

    private static void validateDefaultProvider(EffectiveConfig.ProviderSelection selection, ProviderId defaultProvider) {
        if (selection == EffectiveConfig.ProviderSelection.CODEX && defaultProvider != ProviderId.CODEX
                || selection == EffectiveConfig.ProviderSelection.ANTHROPIC && defaultProvider != ProviderId.ANTHROPIC) {
            throw new ConfigException("routing.default_provider must be one of the enabled providers");
        }
    }

    private static String stripToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
