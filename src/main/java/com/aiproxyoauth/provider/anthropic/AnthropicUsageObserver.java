package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

/** Best-effort usage observer which never changes native SSE delivery. */
public final class AnthropicUsageObserver {
    private final IncrementalSseFramer framer =
            new IncrementalSseFramer(IncrementalSseFramer.DEFAULT_MAX_EVENT_BYTES);
    private long inputTokens;
    private long outputTokens;
    private long cacheCreationInputTokens;
    private long cacheReadInputTokens;

    public void accept(byte[] bytes) {
        try {
            framer.feed(bytes, event -> observe(event.data()));
        } catch (RuntimeException ignored) {
            // Native proxying must not reject future provider events merely because accounting failed.
        }
    }

    private void observe(String data) {
        try {
            JsonNode event = Json.MAPPER.readTree(data);
            JsonNode usage = "message_start".equals(event.path("type").asText())
                    ? event.path("message").path("usage") : event.path("usage");
            if (usage.has("input_tokens")) inputTokens = usage.path("input_tokens").asLong();
            if (usage.has("output_tokens")) outputTokens = usage.path("output_tokens").asLong();
            if (usage.has("cache_creation_input_tokens")) cacheCreationInputTokens = usage.path("cache_creation_input_tokens").asLong();
            if (usage.has("cache_read_input_tokens")) cacheReadInputTokens = usage.path("cache_read_input_tokens").asLong();
        } catch (Exception ignored) {
            // Usage is diagnostics only.
        }
    }

    public long inputTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }
}
