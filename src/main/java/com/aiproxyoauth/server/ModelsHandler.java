package com.aiproxyoauth.server;

import com.aiproxyoauth.model.CodexModelCatalog;
import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ModelsHandler implements Handler {

    private final ModelCatalog modelCatalog;
    private final AnthropicModelsHandler anthropicHandler;

    public ModelsHandler(ModelResolver modelResolver) {
        this(new CodexModelCatalog(modelResolver));
    }

    public ModelsHandler(ModelCatalog modelCatalog) {
        this(modelCatalog, null);
    }

    public ModelsHandler(ModelCatalog modelCatalog, AnthropicModelsHandler anthropicHandler) {
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.anthropicHandler = anthropicHandler;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        if (AnthropicModelsHandler.isNativeRequest(ctx)) {
            if (anthropicHandler == null) {
                AnthropicMessagesHandler.writeError(ctx, 503, "api_error",
                        "Anthropic provider is not enabled");
            } else {
                anthropicHandler.handle(ctx);
            }
            return;
        }
        try {
            List<ProviderModel> models = modelCatalog.resolveModels();
            List<Map<String, Object>> data = models.stream()
                    .map(model -> Map.<String, Object>of(
                            "id", model.id(),
                            "object", "model",
                            "created", 0,
                            "owned_by", owner(model.provider())
                    ))
                    .toList();
            JsonHelper.toJsonResponse(ctx, Map.of("object", "list", "data", data));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to load models.";
            JsonHelper.toErrorResponse(ctx, msg, 502, "upstream_error");
        }
    }

    private static String owner(ProviderId provider) {
        return switch (provider) {
            case CODEX -> "codex-oauth";
            case ANTHROPIC -> "anthropic-oauth";
        };
    }
}
