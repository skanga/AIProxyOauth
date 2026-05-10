package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.ApiKeyUtils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyServer {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyServer.class);

    private final Javalin app;
    private final ServerConfig config;

    public ProxyServer(ServerConfig config, CodexHttpClient client, ModelResolver modelResolver,
                       UsageTracker usageTracker, ApiKeyStore apiKeyStore) {
        this.config = config;
        if (config.requiresApiKeyEnforcement() && !apiKeyStore.isEnforcing()) {
            throw new IllegalStateException(
                    "API key enforcement is required when binding to a non-loopback host: " + config.host()
            );
        }

        this.app = Javalin.create(javalinConfig -> {
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;

            if (config.allowAnyCors() || !config.allowedCorsOrigins().isEmpty()) {
                javalinConfig.bundledPlugins.enableCors(cors ->
                        cors.addRule(rule -> {
                            if (config.allowAnyCors()) {
                                rule.anyHost();
                            } else {
                                String first = config.allowedCorsOrigins().getFirst();
                                String[] rest = config.allowedCorsOrigins().stream()
                                        .skip(1)
                                        .toArray(String[]::new);
                                rule.allowHost(first, rest);
                            }
                        })
                );
            }

            // API key enforcement (opt-in: only when keys are configured)
            // Enforcement is evaluated once at startup. Keys can be hot-reloaded (which keys
            // are valid changes), but enforcement cannot be toggled on/off without a restart.
            if (apiKeyStore.isEnforcing()) {
                javalinConfig.routes.beforeMatched(ctx -> authenticateRequest(ctx, apiKeyStore));
            }

            // Routes
            javalinConfig.routes.get("/health", new HealthHandler());
            javalinConfig.routes.get("/v1/models", new ModelsHandler(modelResolver));
            javalinConfig.routes.get("/v1/usage", new UsageHandler(usageTracker));
            javalinConfig.routes.post("/v1/responses", new ResponsesHandler(client, config, usageTracker));
            javalinConfig.routes.post("/v1/chat/completions", new ChatCompletionsHandler(client, config, usageTracker));

            // Global exception handler
            javalinConfig.routes.exception(Exception.class, (e, ctx) -> {
                LOG.error("Unhandled request failure for {} {}", ctx.method(), ctx.path(), e);
                JsonHelper.toErrorResponse(ctx, "Unexpected server error.", 500, "server_error");
            });

            // 404 handler
            javalinConfig.routes.error(404, ctx ->
                    JsonHelper.toErrorResponse(ctx, "Route not found.", 404, "not_found_error")
            );
        });
    }

    static void authenticateRequest(Context ctx, ApiKeyStore apiKeyStore) {
        if ("/health".equals(ctx.path())) return;
        if (isCorsPreflight(ctx)) return;
        String auth = ctx.header("Authorization");
        String key = (auth != null && auth.startsWith("Bearer "))
                ? auth.substring(7).strip() : null;
        if (key != null && key.equals(apiKeyStore.adminKey())) {
            ctx.attribute("isAdmin", true);
            ctx.attribute("adminKeyFingerprint", ApiKeyUtils.fingerprint(key));
            return;
        }
        String name = (key != null) ? apiKeyStore.lookup(key) : null;
        if (name == null) {
            // Reload-then-401: if the keys file changed since last load, reload it now so
            // the next request from this client succeeds. The current request gets a 401
            // which the client is expected to retry; this is intentional by design.
            apiKeyStore.reloadIfFileChanged();
            JsonHelper.toErrorResponse(ctx, "Invalid or missing API key.", 401, "auth_error");
            ctx.skipRemainingHandlers();
        } else {
            ctx.attribute("keyName", name);
            ctx.attribute("keyFingerprint", ApiKeyUtils.fingerprint(key));
        }
    }

    private static boolean isCorsPreflight(Context ctx) {
        return "OPTIONS".equalsIgnoreCase(String.valueOf(ctx.method()))
                && ctx.header("Origin") != null
                && ctx.header("Access-Control-Request-Method") != null;
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
