package com.aiproxyoauth.provider.stream;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CodexContractFixtureTest {

    @Test
    void canonicalEventsRepresentCurrentCodexTextToolsRefusalAndUsageWithoutLoss() {
        List<CompletionEvent> events = List.of(
                new CompletionEvent.Started("resp-1", "gpt-5.5", 1_722_000_000L),
                new CompletionEvent.BlockStarted(0, BlockType.TEXT, "msg-1", null),
                new CompletionEvent.TextDelta(0, "answer"),
                new CompletionEvent.BlockFinished(0),
                new CompletionEvent.BlockStarted(1, BlockType.TOOL_CALL, "call-1", "read"),
                new CompletionEvent.ToolCallArgumentsDelta(1, "{\"path\":\"README.md\"}"),
                new CompletionEvent.BlockFinished(1),
                new CompletionEvent.BlockStarted(2, BlockType.REFUSAL, "refusal-1", null),
                new CompletionEvent.RefusalDelta(2, "cannot comply"),
                new CompletionEvent.BlockFinished(2),
                new CompletionEvent.UsageSnapshot(12, 7, 0, 0),
                new CompletionEvent.Finished(FinishReason.TOOL_CALLS)
        );

        CompletionEvent.BlockStarted toolStart =
                assertInstanceOf(CompletionEvent.BlockStarted.class, events.get(4));
        CompletionEvent.ToolCallArgumentsDelta arguments =
                assertInstanceOf(CompletionEvent.ToolCallArgumentsDelta.class, events.get(5));
        CompletionEvent.RefusalDelta refusal =
                assertInstanceOf(CompletionEvent.RefusalDelta.class, events.get(8));
        CompletionEvent.UsageSnapshot usage =
                assertInstanceOf(CompletionEvent.UsageSnapshot.class, events.get(10));

        assertEquals("call-1", toolStart.id());
        assertEquals("read", toolStart.name());
        assertEquals("{\"path\":\"README.md\"}", arguments.json());
        assertEquals("cannot comply", refusal.refusal());
        assertEquals(12, usage.inputTokens());
        assertEquals(7, usage.outputTokens());
    }
}
