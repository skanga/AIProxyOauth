package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.ProviderError;
import com.aiproxyoauth.provider.stream.BlockType;
import com.aiproxyoauth.provider.stream.CompletionEvent;
import com.aiproxyoauth.provider.stream.FinishReason;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicStreamDecoderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void arbitraryByteSplitsPreserveCompleteCanonicalLifecycle() {
        String wire = event("message_start", """
                {"message":{"id":"msg_1","model":"claude-sonnet-4-5",
                "usage":{"input_tokens":5,"output_tokens":0,
                "cache_creation_input_tokens":2,"cache_read_input_tokens":3}}}
                """)
                + event("ping", "{}")
                + event("content_block_start", """
                {"index":0,"content_block":{"type":"text","text":""}}
                """)
                + event("content_block_delta", """
                {"index":0,"delta":{"type":"text_delta","text":"A🙂"}}
                """)
                + event("content_block_stop", "{\"index\":0}")
                + event("content_block_start", """
                {"index":1,"content_block":{"type":"thinking","thinking":""}}
                """)
                + event("content_block_delta", """
                {"index":1,"delta":{"type":"thinking_delta","thinking":"plan"}}
                """)
                + event("content_block_delta", """
                {"index":1,"delta":{"type":"signature_delta","signature":"signed"}}
                """)
                + event("content_block_stop", "{\"index\":1}")
                + event("content_block_start", """
                {"index":2,"content_block":{"type":"tool_use","id":"tool-1",
                "name":"read","input":{}}}
                """)
                + event("content_block_delta", """
                {"index":2,"delta":{"type":"input_json_delta","partial_json":"{\\\"path\\\":"}}
                """)
                + event("content_block_delta", """
                {"index":2,"delta":{"type":"input_json_delta","partial_json":"\\\"x\\\"}"}}
                """)
                + event("content_block_stop", "{\"index\":2}")
                + event("message_delta", """
                {"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}
                """)
                + event("message_stop", "{}");
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(CLOCK);
        List<CompletionEvent> events = new ArrayList<>();

        for (byte value : wire.getBytes(StandardCharsets.UTF_8)) {
            events.addAll(decoder.feed(new byte[]{value}));
        }
        events.addAll(decoder.end());

        assertEquals(List.of(
                new CompletionEvent.Started(
                        "msg_1", "claude-sonnet-4-5", CLOCK.instant().getEpochSecond()),
                new CompletionEvent.UsageSnapshot(5, 0, 2, 3),
                new CompletionEvent.Heartbeat(),
                new CompletionEvent.BlockStarted(0, BlockType.TEXT, null, null),
                new CompletionEvent.TextDelta(0, "A🙂"),
                new CompletionEvent.BlockFinished(0),
                new CompletionEvent.BlockStarted(1, BlockType.REASONING, null, null),
                new CompletionEvent.ReasoningDelta(1, "plan"),
                new CompletionEvent.ReasoningSignature(1, "signed"),
                new CompletionEvent.BlockFinished(1),
                new CompletionEvent.BlockStarted(
                        2, BlockType.TOOL_CALL, "tool-1", "read"),
                new CompletionEvent.ToolCallArgumentsDelta(2, "{\"path\":"),
                new CompletionEvent.ToolCallArgumentsDelta(2, "\"x\"}"),
                new CompletionEvent.BlockFinished(2),
                new CompletionEvent.UsageSnapshot(5, 7, 2, 3),
                new CompletionEvent.Finished(FinishReason.TOOL_CALLS)
        ), events);
        assertTrue(decoder.end().isEmpty());
    }

    @Test
    void decodesRedactedThinkingWithoutExposingItAsText() {
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(CLOCK);

        List<CompletionEvent> events = decoder.feed(bytes(
                start()
                        + event("content_block_start", """
                        {"index":0,"content_block":{"type":"redacted_thinking",
                        "data":{"opaque":"value"}}}
                        """)
                        + event("content_block_stop", "{\"index\":0}")
                        + event("message_stop", "{}")
        ));

        assertEquals(BlockType.REDACTED_REASONING,
                ((CompletionEvent.BlockStarted) events.get(1)).type());
        assertEquals("value",
                ((CompletionEvent.RedactedReasoning) events.get(2))
                        .data().path("opaque").asText());
    }

    @Test
    void malformedKnownEventAndTruncatedStreamProduceOneProtocolTerminal() {
        AnthropicStreamDecoder malformed = new AnthropicStreamDecoder(CLOCK);
        List<CompletionEvent> malformedEvents = malformed.feed(bytes(
                event("content_block_delta", "{bad}")
                        + event("message_stop", "{}")
        ));

        assertEquals(1, malformedEvents.size());
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) malformedEvents.getFirst()).error().kind()
        );
        assertTrue(malformed.end().isEmpty());

        AnthropicStreamDecoder truncated = new AnthropicStreamDecoder(CLOCK);
        truncated.feed(bytes(event("message_start", """
                {"message":{"id":"msg","model":"claude","usage":{}}}
                """)));
        List<CompletionEvent> end = truncated.end();
        assertEquals(1, end.size());
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) end.getFirst()).error().kind()
        );
        assertTrue(truncated.end().isEmpty());
    }

    @Test
    void oversizedUnterminatedFrameFailsBoundedly() {
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(CLOCK, 1024);

        List<CompletionEvent> events =
                decoder.feed(("data: " + "x".repeat(2048)).getBytes(StandardCharsets.UTF_8));

        assertEquals(1, events.size());
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) events.getFirst()).error().kind()
        );
    }

    @Test
    void invalidUtf8AndTypedUpstreamErrorsAreTerminal() {
        AnthropicStreamDecoder invalidUtf8 = new AnthropicStreamDecoder(CLOCK);
        List<CompletionEvent> invalidEvents =
                invalidUtf8.feed(new byte[]{'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, '\n', '\n'});
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) invalidEvents.getFirst()).error().kind()
        );

        AnthropicStreamDecoder overloaded = new AnthropicStreamDecoder(CLOCK);
        List<CompletionEvent> errorEvents = overloaded.feed(bytes(event("error", """
                {"type":"error","error":{"type":"overloaded_error","message":"busy"}}
                """)));
        CompletionEvent.Error error = (CompletionEvent.Error) errorEvents.getFirst();
        assertEquals(ProviderError.Kind.OVERLOADED, error.error().kind());
        assertEquals(529, error.error().httpStatus());
        assertTrue(overloaded.end().isEmpty());
    }

    @Test
    void mapsEveryStopReason() {
        assertEquals(FinishReason.STOP, finish("end_turn"));
        assertEquals(FinishReason.STOP, finish("stop_sequence"));
        assertEquals(FinishReason.TOOL_CALLS, finish("tool_use"));
        assertEquals(FinishReason.LENGTH, finish("max_tokens"));
        assertEquals(FinishReason.UNSPECIFIED, finish("future_reason"));
    }

    private static FinishReason finish(String reason) {
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(CLOCK);
        List<CompletionEvent> events = decoder.feed(bytes(
                start() + event("message_delta",
                        "{\"delta\":{\"stop_reason\":\"" + reason + "\"}}")
                        + event("message_stop", "{}")
        ));
        return ((CompletionEvent.Finished) events.getLast()).reason();
    }

    private static String event(String name, String data) {
        String payload = data.strip().lines()
                .map(line -> "data: " + line)
                .collect(java.util.stream.Collectors.joining("\n"));
        return "event: " + name + "\n" + payload + "\n\n";
    }

    private static String start() {
        return event("message_start", """
                {"message":{"id":"msg","model":"claude"}}
                """);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
