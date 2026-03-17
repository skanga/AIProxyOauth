package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.Javalin;

public class ProxyServer {

    private final Javalin app;
    private final ServerConfig config;

    public ProxyServer(ServerConfig config, CodexHttpClient client, ModelResolver modelResolver,
                       UsageTracker usageTracker, ApiKeyStore apiKeyStore) {
        this.config = config;

        this.app = Javalin.create(javalinConfig -> {
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;

            // CORS — handles OPTIONS preflight automatically
            javalinConfig.bundledPlugins.enableCors(cors ->
                    cors.addRule(rule -> rule.anyHost())
            );

            // API key enforcement (opt-in: only when keys are configured)
            // Enforcement is evaluated once at startup. Keys can be hot-reloaded (which keys
            // are valid changes), but enforcement cannot be toggled on/off without a restart.
            if (apiKeyStore.isEnforcing()) {
                javalinConfig.routes.beforeMatched(ctx -> {
                    if ("/health".equals(ctx.path())) return;
                    String auth = ctx.header("Authorization");
                    String key = (auth != null && auth.startsWith("Bearer "))
                            ? auth.substring(7).strip() : null;
                    if (key != null && key.equals(apiKeyStore.adminKey())) {
                        ctx.attribute("isAdmin", true);
                        return;
                    }
                    String name = (key != null) ? apiKeyStore.lookup(key) : null;
                    if (name == null) {
                        // Reload-then-401: if the keys file changed since last load, reload it now so
                        // the *next* request from this client succeeds. The current request gets a 401
                        // which the client is expected to retry — this is intentional by design.
                        apiKeyStore.reloadIfFileChanged();
                        JsonHelper.toErrorResponse(ctx, "Invalid or missing API key.", 401, "auth_error");
                        ctx.skipRemainingHandlers();
                    } else {
                        ctx.attribute("keyName", name);
                    }
                });
            }

            // Routes
            javalinConfig.routes.get("/health", new HealthHandler());
            javalinConfig.routes.get("/v1/models", new ModelsHandler(modelResolver));
            javalinConfig.routes.get("/v1/usage", new UsageHandler(usageTracker));
            javalinConfig.routes.post("/v1/responses", new ResponsesHandler(client, config, usageTracker));
            javalinConfig.routes.post("/v1/chat/completions", new ChatCompletionsHandler(client, config, usageTracker));

            // Global exception handler
            javalinConfig.routes.exception(Exception.class, (e, ctx) -> {
                String message = e.getMessage() != null ? e.getMessage() : "Unexpected server error.";
                JsonHelper.toErrorResponse(ctx, message, 500, "server_error");
            });

            // 404 handler
            javalinConfig.routes.error(404, ctx ->
                    JsonHelper.toErrorResponse(ctx, "Route not found.", 404, "not_found_error")
            );
        });
    }

    public void start() {
        app.start(config.host(), config.port());
    }

    public void stop() {
        app.stop();
    }

    public Javalin getApp() {
        return app;
    }
}
