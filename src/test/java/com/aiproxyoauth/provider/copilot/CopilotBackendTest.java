package com.aiproxyoauth.provider.copilot;

import com.aiproxyoauth.config.EffectiveConfig;
import com.aiproxyoauth.model.CopilotModelCatalog;
import com.aiproxyoauth.provider.*;
import com.aiproxyoauth.server.CopilotBackend;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.util.Json;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CopilotBackendTest {
    @TempDir Path temp;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/chat/completions", "/responses", "/v1/messages"})
    void exposesChatAndResponsesWithStreamingUsageAndIsolatedReplay(String protocol) throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        AtomicReference<String> sent = new AtomicReference<>();
        upstream.createContext("/user", ex -> respond(ex, "{\"endpoints\":{\"api\":\"" + base + "\"}}"));
        upstream.createContext("/models", ex -> respond(ex, "{\"data\":[{\"id\":\"test\",\"supported_endpoints\":[\"" + protocol + "\"],\"capabilities\":{\"type\":\"chat\"}}]}"));
        upstream.createContext(protocol, ex -> {
            sent.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String wire = "data: {\"id\":\"one\",\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}\n\n"
                    + "data: [DONE]\n\n";
            if (protocol.equals("/responses")) wire =
                    "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_one\",\"model\":\"test\"}}\n\n"
                    + "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hello\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n";
            if (protocol.equals("/v1/messages")) wire =
                    "event: message_start\ndata: {\"message\":{\"id\":\"msg_one\",\"model\":\"test\",\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}\n\n"
                    + "event: content_block_start\ndata: {\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Hello\"}}\n\n"
                    + "event: content_block_stop\ndata: {\"index\":0}\n\n"
                    + "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n"
                    + "event: message_stop\ndata: {}\n\n";
            respond(ex, wire);
        });
        upstream.start();
        Javalin[] app = new Javalin[1];
        try (HttpClient http = HttpClient.newHttpClient()) {
            var cfg = new EffectiveConfig.Copilot("github.com", temp.resolve("auth"), "client", null, "token", List.of());
            var client = new CopilotClient(cfg, http, Clock.systemUTC(), URI.create(base + "/user"));
            var backend = new CopilotBackend(client, new CopilotModelCatalog(client, List.of(), Clock.systemUTC()),
                    new UsageTracker(), new RequestLogger(false, temp));
            app[0] = Javalin.create(c -> {
                c.routes.before(ctx -> ctx.attribute("keyFingerprint", ctx.header("X-Test-Key")));
                c.routes.post("/v1/chat/completions", ctx -> backend.handle(ctx, new ModelRoute(ProviderId.COPILOT, "copilot/test", "test", null)));
                c.routes.post("/v1/responses", ctx -> backend.handle(ctx, new ModelRoute(ProviderId.COPILOT, "copilot/test", "test", null)));
            }).start("127.0.0.1", 0);
            String local = "http://127.0.0.1:" + app[0].port();
            var chat = post(http, local + "/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "a");
            assertEquals(200, chat.statusCode(), chat.body());
            assertEquals("Hello", Json.MAPPER.readTree(chat.body()).at("/choices/0/message/content").asString());
            assertEquals(4, Json.MAPPER.readTree(chat.body()).at("/usage/total_tokens").asInt());
            var response = post(http, local + "/v1/responses", "{\"input\":\"first\"}", "a");
            assertEquals(200, response.statusCode(), response.body());
            String id = Json.MAPPER.readTree(response.body()).path("id").asString();
            var replay = post(http, local + "/v1/responses", "{\"input\":\"second\",\"previous_response_id\":\"" + id + "\"}", "a");
            assertEquals(200, replay.statusCode(), replay.body());
            assertTrue(sent.get().contains("first")); assertTrue(sent.get().contains("Hello"));
            assertEquals(400, post(http, local + "/v1/responses", "{\"input\":\"second\",\"previous_response_id\":\"" + id + "\"}", "b").statusCode());
            var stream = post(http, local + "/v1/chat/completions", "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "a");
            assertEquals(200, stream.statusCode()); assertTrue(stream.body().contains("Hello"));
            assertEquals(1, stream.body().split("\\[DONE\\]", -1).length - 1);
            var responseStream = post(http, local + "/v1/responses", "{\"input\":\"hi\",\"stream\":true}", "a");
            assertEquals(200, responseStream.statusCode());
            assertTrue(responseStream.body().contains("event: response.completed"));
            assertTrue(responseStream.body().contains("Hello"));
        } finally { if (app[0] != null) app[0].stop(); upstream.stop(0); }
    }
    private static HttpResponse<String> post(HttpClient http, String url, String body, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("X-Test-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
    }
}
