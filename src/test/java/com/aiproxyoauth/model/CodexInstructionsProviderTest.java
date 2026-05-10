package com.aiproxyoauth.model;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CodexInstructionsProviderTest {

    @TempDir Path tempDir;

    @Test void configuredModeReturnsConfiguredInstructionsWithoutFetching() {
        AtomicInteger calls = new AtomicInteger();
        CodexInstructionsProvider provider = new CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.CONFIGURED,
                "local instructions",
                tempDir,
                Duration.ofMinutes(15),
                fixedClock("2026-05-10T12:00:00Z"),
                request -> {
                    calls.incrementAndGet();
                    return new CodexInstructionsProvider.FetchResponse(200, "remote", Map.of());
                });

        assertEquals("local instructions", provider.instructionsForModel("gpt-5.2-codex-high"));
        assertEquals(0, calls.get());
    }

    @Test void latestCodexFetchesAndReusesFreshCacheByModelFamily() {
        AtomicInteger calls = new AtomicInteger();
        CodexInstructionsProvider provider = new CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.LATEST_CODEX,
                "local fallback",
                tempDir,
                Duration.ofMinutes(15),
                fixedClock("2026-05-10T12:00:00Z"),
                request -> {
                    calls.incrementAndGet();
                    assertEquals("gpt-5.2-codex", request.modelFamily());
                    return new CodexInstructionsProvider.FetchResponse(
                            200,
                            "{\"instructions\":\"remote instructions\"}",
                            Map.of("ETag", "\"abc123\""));
                });

        assertEquals("remote instructions", provider.instructionsForModel("gpt-5.2-codex-high"));
        assertEquals("remote instructions", provider.instructionsForModel("gpt-5.2-codex-medium"));
        assertEquals(1, calls.get());
    }

    @Test void latestCodexFallsBackToStaleCacheOnFetchFailure() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-10T12:00:00Z"));
        CodexInstructionsProvider provider = new CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.LATEST_CODEX,
                "local fallback",
                tempDir,
                Duration.ofMinutes(15),
                clock,
                request -> {
                    if (calls.incrementAndGet() == 1) {
                        return new CodexInstructionsProvider.FetchResponse(200, "cached instructions", Map.of());
                    }
                    throw new RuntimeException("network unavailable");
                });

        assertEquals("cached instructions", provider.instructionsForModel("gpt-5.1-codex-max-xhigh"));
        clock.advance(Duration.ofMinutes(16));
        assertEquals("cached instructions", provider.instructionsForModel("gpt-5.1-codex-max-xhigh"));
        assertEquals(2, calls.get());
    }

    @Test void latestCodexFallsBackToConfiguredInstructionsWhenFetchFailsWithoutCache() {
        CodexInstructionsProvider provider = new CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.LATEST_CODEX,
                "local fallback",
                tempDir,
                Duration.ofMinutes(15),
                fixedClock("2026-05-10T12:00:00Z"),
                request -> {
                    throw new RuntimeException("network unavailable");
                });

        assertEquals("local fallback", provider.instructionsForModel("gpt-5.2-codex-high"));
    }

    @Test void latestCodexCacheMetadataRecordsFetchDetails() throws Exception {
        CodexInstructionsProvider provider = new CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.LATEST_CODEX,
                "local fallback",
                tempDir,
                Duration.ofMinutes(15),
                fixedClock("2026-05-10T12:00:00Z"),
                request -> new CodexInstructionsProvider.FetchResponse(
                        200,
                        "remote instructions",
                        Map.of("ETag", "\"etag-value\"")));

        assertEquals("remote instructions", provider.instructionsForModel("gpt-5.2-codex-high"));

        Path cacheFile = tempDir.resolve("gpt-5.2-codex.json");
        assertTrue(Files.exists(cacheFile));
        JsonNode cached = Json.MAPPER.readTree(Files.readString(cacheFile));
        assertEquals("gpt-5.2-codex", cached.path("modelFamily").asText());
        assertEquals("https://chatgpt.com/backend-api/codex/instructions/gpt-5.2-codex", cached.path("sourceUrl").asText());
        assertEquals("\"etag-value\"", cached.path("etag").asText());
        assertEquals("2026-05-10T12:00:00Z", cached.path("fetchedAt").asText());
        assertEquals("remote instructions", cached.path("instructions").asText());
    }

    @Test void staleCacheUsesConditionalRequestWhenEtagExists() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-10T12:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        CodexInstructionsProvider provider = new CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.LATEST_CODEX,
                "local fallback",
                tempDir,
                Duration.ofMinutes(15),
                clock,
                request -> {
                    if (calls.incrementAndGet() == 1) {
                        return new CodexInstructionsProvider.FetchResponse(200, "remote instructions", Map.of("ETag", "\"v1\""));
                    }
                    assertEquals("\"v1\"", request.headers().get("If-None-Match"));
                    return new CodexInstructionsProvider.FetchResponse(304, "", Map.of());
                });

        assertEquals("remote instructions", provider.instructionsForModel("gpt-5.2-codex-high"));
        clock.advance(Duration.ofMinutes(16));
        assertEquals("remote instructions", provider.instructionsForModel("gpt-5.2-codex-high"));
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }
}
