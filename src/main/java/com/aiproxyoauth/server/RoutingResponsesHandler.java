package com.aiproxyoauth.server;

import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.provider.ModelRoute;
import com.aiproxyoauth.provider.ModelRoutingException;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderRouter;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public final class RoutingResponsesHandler implements Handler {
    private final ModelCatalog modelCatalog;
    private final ProviderId defaultProvider;
    private final Map<ProviderId, ResponsesBackend> backends;
    private final String fallbackModel;

    public RoutingResponsesHandler(
            ModelCatalog modelCatalog,
            ProviderId defaultProvider,
            ResponsesBackend codexBackend,
            ResponsesBackend anthropicBackend,
            String fallbackModel
    ) {
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.defaultProvider = Objects.requireNonNull(defaultProvider, "defaultProvider");
        this.fallbackModel = Objects.requireNonNull(fallbackModel, "fallbackModel");
        EnumMap<ProviderId, ResponsesBackend> configured = new EnumMap<>(ProviderId.class);
        if (codexBackend != null) configured.put(ProviderId.CODEX, codexBackend);
        if (anthropicBackend != null) configured.put(ProviderId.ANTHROPIC, anthropicBackend);
        this.backends = Map.copyOf(configured);
    }

    @Override
    public void handle(Context context) throws Exception {
        JsonNode body;
        try {
            body = MAPPER.readTree(context.body());
        } catch (Exception error) {
            JsonHelper.toErrorResponse(context, "Request body must contain valid JSON.");
            return;
        }
        if (body == null || !body.isObject()) {
            JsonHelper.toErrorResponse(context, "Request body must be a JSON object.");
            return;
        }
        ModelRoute route;
        try {
            route = new ProviderRouter(modelCatalog.resolveModels(), defaultProvider)
                    .route(body.path("model").asText(fallbackModel));
        } catch (ModelRoutingException error) {
            JsonHelper.toErrorResponse(context, error.getMessage(), 400,
                    "invalid_request_error", "model", "invalid_model");
            return;
        } catch (Exception error) {
            JsonHelper.toErrorResponse(context, "Model catalog is temporarily unavailable.",
                    503, "server_error", "model", "model_catalog_unavailable");
            return;
        }
        ResponsesBackend backend = backends.get(route.provider());
        if (backend == null) {
            JsonHelper.toErrorResponse(context,
                    "The requested model provider is not enabled: " + route.provider().wireName(),
                    400, "invalid_request_error", "model", "provider_not_enabled");
            return;
        }
        AccessLogFields.provider(context, route.provider().wireName());
        backend.handle(context, route);
    }
}
