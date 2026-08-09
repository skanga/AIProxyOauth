package com.aiproxyoauth.model;

import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.transport.BoundedBodyReader;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public final class AnthropicModelResolver implements ProviderModelCatalog {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);
    private static final int DEFAULT_CONTEXT_WINDOW = 200_000;
    private static final List<ProviderModel> SEED_MODELS = assignFamilyAliases(List.of(
            baseModel("claude-opus-4-5", "Claude Opus 4.5"),
            baseModel("claude-sonnet-4-5", "Claude Sonnet 4.5"),
            baseModel("claude-haiku-4-5", "Claude Haiku 4.5")
    ));

    public enum Source {
        NOT_RESOLVED,
        DISCOVERED,
        CACHE,
        LAST_GOOD,
        CONFIGURED_FALLBACK,
        SEED_FALLBACK
    }

    public enum FailureKind {
        AUTHENTICATION,
        PERMISSION,
        TRANSPORT,
        INVALID_RESPONSE,
        EMPTY_CATALOG
    }

    public record Failure(FailureKind kind, String message) {
        public Failure {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
        }
    }

    private final AnthropicHttpClient transport;
    private final AnthropicCompatibilityProfile profile;
    private final List<String> configuredModels;
    private final Clock clock;
    private final Duration cacheTtl;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile List<ProviderModel> cached;
    private volatile List<ProviderModel> lastGood;
    private volatile Instant cacheExpiresAt = Instant.MIN;
    private volatile Source source = Source.NOT_RESOLVED;
    private volatile Failure lastFailure;

    public AnthropicModelResolver(
            AnthropicHttpClient transport,
            AnthropicCompatibilityProfile profile,
            List<String> configuredModels,
            Clock clock
    ) {
        this(transport, profile, configuredModels, clock, DEFAULT_CACHE_TTL);
    }

    AnthropicModelResolver(
            AnthropicHttpClient transport,
            AnthropicCompatibilityProfile profile,
            List<String> configuredModels,
            Clock clock,
            Duration cacheTtl
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.configuredModels = configuredModels == null
                ? List.of()
                : configuredModels.stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl cannot be negative");
        }
    }

    @Override
    public ProviderId provider() {
        return ProviderId.ANTHROPIC;
    }

    @Override
    public List<ProviderModel> resolveModels() {
        if (!configuredModels.isEmpty()) {
            source = Source.CONFIGURED_FALLBACK;
            return assignFamilyAliases(configuredModels.stream()
                    .distinct()
                    .map(id -> baseModel(id, id))
                    .toList());
        }
        Instant now = clock.instant();
        List<ProviderModel> snapshot = cached;
        if (snapshot != null && now.isBefore(cacheExpiresAt)) {
            source = Source.CACHE;
            return snapshot;
        }

        lock.lock();
        try {
            now = clock.instant();
            snapshot = cached;
            if (snapshot != null && now.isBefore(cacheExpiresAt)) {
                source = Source.CACHE;
                return snapshot;
            }
            try {
                List<ProviderModel> discovered = discover();
                cached = discovered;
                lastGood = discovered;
                cacheExpiresAt = now.plus(cacheTtl);
                lastFailure = null;
                source = Source.DISCOVERED;
                return discovered;
            } catch (DiscoveryException error) {
                lastFailure = new Failure(error.kind, error.getMessage());
                if (lastGood != null) {
                    source = Source.LAST_GOOD;
                    return lastGood;
                }
                if (!configuredModels.isEmpty()) {
                    source = Source.CONFIGURED_FALLBACK;
                    return assignFamilyAliases(configuredModels.stream()
                            .distinct()
                            .map(id -> baseModel(id, id))
                            .toList());
                }
                source = Source.SEED_FALLBACK;
                return SEED_MODELS;
            }
        } finally {
            lock.unlock();
        }
    }

    public Source source() {
        return source;
    }

    public Optional<Failure> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    private List<ProviderModel> discover() throws DiscoveryException {
        HttpResponse<java.io.InputStream> response;
        try {
            response = transport.request(
                    profile.modelsUri(), "GET", null, Map.of(), REQUEST_TIMEOUT);
        } catch (IOException error) {
            throw new DiscoveryException(
                    FailureKind.TRANSPORT, "Anthropic model discovery transport failure", error);
        }

        byte[] body;
        try {
            body = BoundedBodyReader.read(response, MAX_RESPONSE_BYTES);
        } catch (BoundedBodyReader.BodyTooLargeException error) {
            throw new DiscoveryException(
                    FailureKind.INVALID_RESPONSE, "Anthropic model response is too large", error);
        } catch (IOException error) {
            throw new DiscoveryException(
                    FailureKind.TRANSPORT, "Anthropic model response could not be read", error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            FailureKind kind = switch (response.statusCode()) {
                case 401 -> FailureKind.AUTHENTICATION;
                case 403 -> FailureKind.PERMISSION;
                default -> FailureKind.TRANSPORT;
            };
            throw new DiscoveryException(
                    kind, "Anthropic model discovery returned HTTP " + response.statusCode());
        }

        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (IOException error) {
            throw new DiscoveryException(
                    FailureKind.INVALID_RESPONSE, "Anthropic returned invalid model JSON", error);
        }
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray()) {
            throw new DiscoveryException(
                    FailureKind.INVALID_RESPONSE, "Anthropic returned an invalid model catalog");
        }

        Map<String, ProviderModel> models = new LinkedHashMap<>();
        for (JsonNode value : data) {
            String id = value.path("id").asText();
            if (id.isBlank()) {
                continue;
            }
            String displayName = value.path("display_name").asText(id);
            models.putIfAbsent(id, baseModel(id, displayName));
        }
        if (models.isEmpty()) {
            throw new DiscoveryException(
                    FailureKind.EMPTY_CATALOG, "Anthropic returned an empty model catalog");
        }
        return assignFamilyAliases(List.copyOf(models.values()));
    }

    private static ProviderModel baseModel(String id, String displayName) {
        return new ProviderModel(
                id,
                displayName,
                ProviderId.ANTHROPIC,
                List.of(),
                Optional.empty(),
                DEFAULT_CONTEXT_WINDOW
        );
    }

    private static List<ProviderModel> assignFamilyAliases(List<ProviderModel> models) {
        java.util.Set<String> assignedFamilies = new java.util.HashSet<>();
        List<ProviderModel> result = new ArrayList<>(models.size());
        for (ProviderModel model : models) {
            String family = family(model.id());
            List<String> aliases = family != null && assignedFamilies.add(family)
                    ? List.of("anthropic/" + family, family)
                    : List.of();
            result.add(new ProviderModel(
                    model.id(),
                    model.displayName(),
                    model.provider(),
                    aliases,
                    model.supportsTools(),
                    model.contextWindow()
            ));
        }
        return List.copyOf(result);
    }

    private static String family(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        if (normalized.contains("opus")) {
            return "opus";
        }
        if (normalized.contains("sonnet")) {
            return "sonnet";
        }
        if (normalized.contains("haiku")) {
            return "haiku";
        }
        return null;
    }

    private static final class DiscoveryException extends Exception {
        private final FailureKind kind;

        private DiscoveryException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        private DiscoveryException(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }
}
