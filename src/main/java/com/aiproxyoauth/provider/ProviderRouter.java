package com.aiproxyoauth.provider;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ProviderRouter {

    private final List<ProviderModel> models;
    private final ProviderId defaultProvider;

    public ProviderRouter(List<ProviderModel> models, ProviderId defaultProvider) {
        this.models = List.copyOf(Objects.requireNonNull(models, "models"));
        this.defaultProvider = Objects.requireNonNull(defaultProvider, "defaultProvider");
    }

    public ModelRoute route(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            throw new ModelRoutingException(
                    ModelRoutingException.Kind.BLANK_MODEL,
                    "Model cannot be blank."
            );
        }
        String requested = requestedModel.strip();
        ModelRoute qualified = routeQualified(requested);
        if (qualified != null) {
            return qualified;
        }

        List<ProviderModel> exact = matchingModels(requested, null);
        if (exact.size() == 1) {
            return route(exact.getFirst(), requested);
        }
        if (exact.size() > 1) {
            List<String> alternatives = exact.stream()
                    .map(model -> model.provider().wireName() + "/" + model.id())
                    .distinct()
                    .sorted()
                    .toList();
            throw new ModelRoutingException(
                    ModelRoutingException.Kind.AMBIGUOUS,
                    "Model is available from multiple providers: " + requested,
                    alternatives
            );
        }

        String normalized = requested.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("claude-")) {
            return new ModelRoute(ProviderId.ANTHROPIC, requested, requested, null);
        }
        if (normalized.startsWith("gpt-") || normalized.startsWith("codex-")) {
            return new ModelRoute(ProviderId.CODEX, requested, requested, null);
        }
        return new ModelRoute(defaultProvider, requested, requested, null);
    }

    private ModelRoute routeQualified(String requested) {
        int separator = requested.indexOf('/');
        if (separator < 0) {
            return null;
        }
        String providerName = requested.substring(0, separator);
        String unqualified = requested.substring(separator + 1);
        ProviderId provider;
        try {
            provider = ProviderId.parse(providerName);
        } catch (IllegalArgumentException error) {
            throw new ModelRoutingException(
                    ModelRoutingException.Kind.UNKNOWN_PROVIDER,
                    "Unsupported model provider: " + providerName
            );
        }
        if (unqualified.isBlank()) {
            throw new ModelRoutingException(
                    ModelRoutingException.Kind.BLANK_MODEL,
                    "Qualified model cannot be blank."
            );
        }

        List<ProviderModel> matching = matchingModels(requested, provider);
        if (matching.isEmpty()) {
            matching = matchingModels(unqualified, provider);
        }
        if (matching.size() > 1) {
            List<String> alternatives = matching.stream()
                    .map(model -> provider.wireName() + "/" + model.id())
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .toList();
            throw new ModelRoutingException(
                    ModelRoutingException.Kind.AMBIGUOUS,
                    "Qualified model alias is ambiguous: " + requested,
                    alternatives
            );
        }
        String upstream = matching.isEmpty() ? unqualified : matching.getFirst().id();
        return new ModelRoute(provider, requested, upstream, null);
    }

    private List<ProviderModel> matchingModels(String name, ProviderId provider) {
        return models.stream()
                .filter(model -> provider == null || model.provider() == provider)
                .filter(model -> model.id().equals(name) || model.aliases().contains(name))
                .toList();
    }

    private static ModelRoute route(ProviderModel model, String requested) {
        return new ModelRoute(model.provider(), requested, model.id(), null);
    }
}
