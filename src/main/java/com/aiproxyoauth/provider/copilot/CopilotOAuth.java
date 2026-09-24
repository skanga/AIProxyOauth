package com.aiproxyoauth.provider.copilot;

import com.aiproxyoauth.config.EffectiveConfig;
import com.aiproxyoauth.util.Json;
import com.aiproxyoauth.transport.BoundedBodyReader;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** GitHub device authorization. No repository scope or client secret is requested. */
public final class CopilotOAuth {
    @FunctionalInterface public interface Exchange { JsonNode post(String path, JsonNode body) throws IOException, InterruptedException; }
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    public static JsonNode authorize(String host, String clientId, Exchange exchange,
                                     PrintWriter out, Sleeper sleep, java.util.function.LongSupplier nanos)
            throws IOException, InterruptedException {
        var start = Json.MAPPER.createObjectNode().put("client_id", clientId);
        JsonNode attempt = exchange.post("/login/device/code", start);
        String code = attempt.path("device_code").asText();
        String userCode = attempt.path("user_code").asText();
        String verification = attempt.path("verification_uri").asText();
        long interval = attempt.path("interval").asLong(-1);
        long lifetime = attempt.path("expires_in").asLong(-1);
        if (code.isBlank() || !userCode.matches("[A-Z0-9-]{4,32}")
                || !verification.equals("https://" + host + "/login/device")
                || interval < 1 || interval > 60 || lifetime < 1 || lifetime > 1800) {
            throw new IOException("Invalid Copilot device authorization response");
        }
        out.println("Open " + verification + " and enter " + userCode);
        out.flush();
        long deadline = nanos.getAsLong() + Duration.ofSeconds(lifetime).toNanos();
        while (nanos.getAsLong() + Duration.ofSeconds(interval).toNanos() < deadline) {
            sleep.sleep(interval * 1000);
            if (nanos.getAsLong() >= deadline) break;
            JsonNode result = exchange.post("/login/oauth/access_token", Json.MAPPER.createObjectNode()
                    .put("client_id", clientId).put("device_code", code)
                    .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
            if (result.path("access_token").isTextual() && !result.path("access_token").asText().isBlank()) return result;
            switch (result.path("error").asText()) {
                case "authorization_pending" -> { }
                case "slow_down" -> interval += 5;
                default -> throw new IOException("Copilot authorization denied, expired, or failed");
            }
        }
        throw new IOException("Copilot device authorization expired");
    }

    public static void login(EffectiveConfig.Copilot config, PrintWriter out) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            JsonNode tokens = authorize(config.githubHost(), config.oauthClientId(), (path, body) -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + config.githubHost() + path))
                        .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                        .header("Content-Type", "application/json").header("User-Agent", "AIProxyOauth")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes = BoundedBodyReader.read(response, 64 * 1024);
                if (response.statusCode() != 200) throw new IOException("Copilot authorization returned HTTP " + response.statusCode());
                try { return Json.MAPPER.readTree(bytes); }
                catch (IOException error) { throw new IOException("Invalid Copilot authorization response"); }
            }, out, Thread::sleep, System::nanoTime);
            new CopilotCredentials(config).save(tokens);
            out.println("Copilot login saved to " + config.oauthFile());
        }
    }
}
