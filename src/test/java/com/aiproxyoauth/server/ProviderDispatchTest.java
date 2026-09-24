package com.aiproxyoauth.server;
import com.aiproxyoauth.provider.*;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ProviderDispatchTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"connection", "timeout"})
    void retriesConnectionFailuresAndTimeoutsBeforeCommitment(String kind) throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        var backends = Map.<ProviderId, ChatBackend>of(ProviderId.COPILOT, (ctx, route) -> {
            if (kind.equals("connection")) throw new java.net.ConnectException();
            throw new java.net.http.HttpTimeoutException("timeout");
        }, ProviderId.CODEX, (ctx, route) -> { fallback.incrementAndGet(); ctx.result("ok"); });
        var handler = new ProviderDispatch(() -> List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)),
                ProviderId.COPILOT, "same", backends, ProviderId.defaultOrder(), true);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals("ok", post(http, app.port(), "{\"model\":\"same\"}").body());
            assertEquals(1, fallback.get());
        } finally { app.stop(); }
    }
    @Test void doesNotAssumeImageCapabilityInFallbackCatalog() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        ChatBackend capable = new ChatBackend() {
            public boolean supports(com.fasterxml.jackson.databind.JsonNode body, ModelRoute route, boolean responses) { return true; }
            public void handle(io.javalin.http.Context ctx, ModelRoute route) throws Exception { throw new UpstreamFailure(503); }
        };
        var handler = new ProviderDispatch(() -> List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)),
                ProviderId.COPILOT, "same", Map.of(ProviderId.COPILOT, capable, ProviderId.CODEX,
                (ChatBackend) (ctx, route) -> { fallback.incrementAndGet(); ctx.result("fallback"); }), ProviderId.defaultOrder(), true);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(503, post(http, app.port(), "{\"model\":\"same\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AQID\"}}]}]}").statusCode());
            assertEquals(0, fallback.get());
        } finally { app.stop(); }
    }
    @Test void neverRetriesAfterWritingAResponseAndSkipsUnsupportedCandidates() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        ChatBackend first = (ctx, route) -> {
            ctx.res().getOutputStream().write("started".getBytes()); ctx.res().getOutputStream().flush();
            throw new UpstreamFailure(503);
        };
        var backends = Map.<ProviderId, ChatBackend>of(ProviderId.COPILOT, first,
                ProviderId.CODEX, (ctx, route) -> { fallback.incrementAndGet(); ctx.result("fallback"); });
        var handler = new ProviderDispatch(() -> List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)),
                ProviderId.COPILOT, "same", backends, ProviderId.defaultOrder(), true);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals("started", post(http, app.port(), "{\"model\":\"same\"}").body());
            assertEquals(0, fallback.get());
        } finally { app.stop(); }
    }
    @Test void pinsCompletedResponsesAcrossCatalogChanges() throws Exception {
        var models = new java.util.concurrent.atomic.AtomicReference<>(List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)));
        AtomicInteger copilot = new AtomicInteger(), codex = new AtomicInteger();
        var backends = Map.<ProviderId, ChatBackend>of(
                ProviderId.COPILOT, (ctx, route) -> { copilot.incrementAndGet(); ctx.attribute("completedResponse",
                        com.aiproxyoauth.util.Json.MAPPER.readTree("{\"id\":\"resp_one\",\"output\":[]}")); ctx.result("copilot"); },
                ProviderId.CODEX, (ctx, route) -> { codex.incrementAndGet(); ctx.result("codex"); });
        var handler = new ProviderDispatch(models::get, ProviderId.COPILOT, "same", backends, ProviderId.defaultOrder(), true);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals("copilot", post(http, app.port(), "{\"model\":\"same\"}").body());
            models.set(List.of(model(ProviderId.CODEX)));
            assertEquals("copilot", post(http, app.port(), "{\"model\":\"same\",\"previous_response_id\":\"resp_one\"}").body());
            assertEquals(2, copilot.get()); assertEquals(0, codex.get());
            assertEquals(400, post(http, app.port(), "{\"model\":\"codex/same\",\"previous_response_id\":\"resp_one\"}").statusCode());
            assertEquals(400, post(http, app.port(), "{\"model\":\"same\",\"previous_response_id\":\"resp_copilot_evicted\"}").statusCode());
        } finally { app.stop(); }
    }
    @Test void retriesExactModelInOrderButNeverAuthQualifiedCommittedOrReplayRequests() throws Exception {
        var models = List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX));
        AtomicInteger status = new AtomicInteger(429), attempts = new AtomicInteger();
        var backend = Map.<ProviderId, ChatBackend>of(
                ProviderId.COPILOT, (ctx, route) -> { attempts.incrementAndGet(); throw new UpstreamFailure(status.get()); },
                ProviderId.CODEX, (ctx, route) -> { attempts.incrementAndGet(); ctx.result("codex"); });
        var dispatch = new ProviderDispatch(() -> models, ProviderId.COPILOT, "same", backend, ProviderId.defaultOrder(), true);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", dispatch)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            var result = post(http, app.port(), "{\"model\":\"same\"}");
            assertEquals(200, result.statusCode()); assertEquals("codex", result.body()); assertEquals(2, attempts.get());
            attempts.set(0); status.set(403);
            assertEquals(403, post(http, app.port(), "{\"model\":\"same\"}").statusCode()); assertEquals(1, attempts.get());
            attempts.set(0); status.set(503);
            assertEquals(503, post(http, app.port(), "{\"model\":\"copilot/same\"}").statusCode()); assertEquals(1, attempts.get());
            attempts.set(0);
            assertEquals(400, post(http, app.port(), "{\"model\":\"same\",\"previous_response_id\":\"missing\"}").statusCode()); assertEquals(0, attempts.get());
        } finally { app.stop(); }
    }
    private static ProviderModel model(ProviderId id) { return new ProviderModel("same", "same", id, List.of(), Optional.of(true), 1000); }
    private static HttpResponse<String> post(HttpClient http, int port, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
