package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.ModelRoute;
import com.aiproxyoauth.provider.ModelRoutingException;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderRouter;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** Minimal mutation required to send a native Messages request with Claude Code OAuth. */
public final class AnthropicNativeRequest {
    private AnthropicNativeRequest() {
    }

    public static Prepared prepare(
            ObjectNode input,
            ProviderRouter router,
            AnthropicCompatibilityProfile profile
    ) throws AnthropicTranslationException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(profile, "profile");
        ObjectNode body = input.deepCopy();
        JsonNode modelNode = body.get("model");
        if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
            throw invalid("`model` must be a non-empty string");
        }
        String requestedModel = modelNode.asText().strip();
        String normalizedModel = requestedModel.toLowerCase(java.util.Locale.ROOT);
        if (requestedModel.indexOf('/') < 0
                && (normalizedModel.startsWith("gpt-")
                || normalizedModel.startsWith("codex-"))) {
            throw invalid("Native Messages requests require an Anthropic model");
        }
        ModelRoute route;
        try {
            route = router.route(requestedModel.indexOf('/') >= 0
                    ? requestedModel : "anthropic/" + requestedModel);
        } catch (ModelRoutingException error) {
            throw invalid(error.getMessage());
        }
        if (route.provider() != ProviderId.ANTHROPIC) {
            throw invalid("Native Messages requests require an Anthropic model");
        }
        body.put("model", route.upstreamModel());

        JsonNode stream = body.get("stream");
        if (stream != null && !stream.isNull() && !stream.isBoolean()) {
            throw invalid("`stream` must be a boolean");
        }
        boolean streaming = stream != null && stream.asBoolean(false);

        ArrayNode system = Json.MAPPER.createArrayNode();
        system.addObject().put("type", "text").put("text", profile.oauthSystemPreamble());
        JsonNode suppliedSystem = body.get("system");
        if (suppliedSystem == null || suppliedSystem.isNull()) {
            // The OAuth preamble is the complete system prompt.
        } else if (suppliedSystem.isTextual()) {
            system.addObject().put("type", "text").put("text", suppliedSystem.asText());
        } else if (suppliedSystem.isArray()) {
            suppliedSystem.forEach(value -> system.add(value.deepCopy()));
        } else {
            throw invalid("`system` must be a string or array");
        }
        body.set("system", system);
        return new Prepared(body, streaming);
    }

    private static AnthropicTranslationException invalid(String message) {
        return new AnthropicTranslationException(message);
    }

    public record Prepared(ObjectNode body, boolean stream) {
        public Prepared {
            body = Objects.requireNonNull(body, "body");
        }
    }
}
