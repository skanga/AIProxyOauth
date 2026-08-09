package com.aiproxyoauth;

import com.aiproxyoauth.provider.ProviderId;

import java.util.EnumSet;
import java.util.Set;

final class ProviderStartupResolver {
    private ProviderStartupResolver() {
    }

    static Set<ProviderId> resolve(
            String configuredProviders,
            boolean codexCredentialAvailable,
            boolean anthropicCredentialAvailable
    ) {
        EnumSet<ProviderId> available = EnumSet.noneOf(ProviderId.class);
        if (codexCredentialAvailable) {
            available.add(ProviderId.CODEX);
        }
        if (anthropicCredentialAvailable) {
            available.add(ProviderId.ANTHROPIC);
        }

        if (configuredProviders == null || configuredProviders.isBlank()) {
            if (available.isEmpty()) {
                throw new IllegalArgumentException(
                        "No usable Codex or Anthropic OAuth credential was found");
            }
            return Set.copyOf(available);
        }

        EnumSet<ProviderId> requested = EnumSet.noneOf(ProviderId.class);
        for (String value : configuredProviders.split(",")) {
            if (!value.isBlank()) {
                requested.add(ProviderId.parse(value));
            }
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("--provider must select at least one provider");
        }
        EnumSet<ProviderId> unavailable = EnumSet.copyOf(requested);
        unavailable.removeAll(available);
        if (!unavailable.isEmpty()) {
            throw new IllegalArgumentException(
                    "No usable OAuth credential for requested provider(s): "
                            + unavailable.stream().map(ProviderId::wireName).toList()
            );
        }
        return Set.copyOf(requested);
    }

    static ProviderId resolveDefault(String configuredDefault, Set<ProviderId> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            throw new IllegalArgumentException("At least one provider must be enabled");
        }
        if (configuredDefault == null || configuredDefault.isBlank()) {
            return enabled.contains(ProviderId.CODEX)
                    ? ProviderId.CODEX : enabled.iterator().next();
        }
        ProviderId requested = ProviderId.parse(configuredDefault);
        if (!enabled.contains(requested)) {
            throw new IllegalArgumentException(
                    "--default-provider must name an enabled provider: " + requested.wireName());
        }
        return requested;
    }
}
