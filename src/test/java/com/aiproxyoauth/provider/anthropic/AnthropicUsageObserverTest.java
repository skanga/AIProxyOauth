package com.aiproxyoauth.provider.anthropic;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnthropicUsageObserverTest {
    @Test
    void cacheReadAndCreationTokensRemainInTotalAcrossPartialUpdates() {
        var observer = new AnthropicUsageObserver();
        observer.accept(("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{"
                + "\"input_tokens\":3,\"output_tokens\":0,\"cache_creation_input_tokens\":20,\"cache_read_input_tokens\":100}}}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        observer.accept("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":14}}\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(123, observer.inputTokens());
        assertEquals(14, observer.outputTokens());
    }
}
