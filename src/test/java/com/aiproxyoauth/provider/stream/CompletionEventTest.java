package com.aiproxyoauth.provider.stream;

import com.aiproxyoauth.provider.ProviderError;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletionEventTest {

    @Test
    void blockAwareEventsPreserveOrderingIdentityAndSignatures() {
        CompletionEvent.BlockStarted start = new CompletionEvent.BlockStarted(
                2, BlockType.REASONING, "reasoning-2", null);
        CompletionEvent.ReasoningDelta delta = new CompletionEvent.ReasoningDelta(2, "plan");
        CompletionEvent.ReasoningSignature signature =
                new CompletionEvent.ReasoningSignature(2, "signed");
        CompletionEvent.BlockFinished finish = new CompletionEvent.BlockFinished(2);

        assertEquals(2, start.index());
        assertEquals("reasoning-2", start.id());
        assertEquals(2, delta.index());
        assertEquals("signed", signature.signature());
        assertEquals(2, finish.index());
    }

    @Test
    void redactedReasoningDefensivelyCopiesOpaquePayload() {
        var payload = JsonNodeFactory.instance.objectNode().put("data", "opaque");
        CompletionEvent.RedactedReasoning event =
                new CompletionEvent.RedactedReasoning(0, payload);

        payload.put("data", "mutated");

        assertEquals("opaque", event.data().path("data").asText());
    }

    @Test
    void usageIsAnExplicitNonNegativeCumulativeSnapshot() {
        CompletionEvent.UsageSnapshot start =
                new CompletionEvent.UsageSnapshot(10, 0, 2, 3);
        CompletionEvent.UsageSnapshot terminal =
                new CompletionEvent.UsageSnapshot(10, 7, 2, 3);

        assertEquals(7, terminal.outputTokens());
        assertEquals(3, terminal.cacheReadInputTokens());
        assertThrows(IllegalArgumentException.class, () ->
                new CompletionEvent.UsageSnapshot(-1, 0, 0, 0));
        assertEquals(10, start.inputTokens());
    }

    @Test
    void typedProtocolErrorCanRepresentTruncatedStreams() {
        ProviderError error = ProviderError.of(
                ProviderError.Kind.PROTOCOL,
                "Anthropic stream ended before message_stop"
        );
        CompletionEvent.Error event = new CompletionEvent.Error(error);

        assertEquals(502, event.error().httpStatus());
        assertEquals(ProviderError.Kind.PROTOCOL, event.error().kind());
    }

    @Test
    void eventContractsRejectNegativeBlockIndexes() {
        assertThrows(IllegalArgumentException.class, () ->
                new CompletionEvent.TextDelta(-1, "text"));
        assertThrows(IllegalArgumentException.class, () ->
                new CompletionEvent.BlockFinished(-1));
    }
}
