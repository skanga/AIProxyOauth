package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.stream.BlockType;
import com.aiproxyoauth.provider.stream.CompletionEvent;
import com.aiproxyoauth.provider.stream.FinishReason;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatCompletionEncoderTest {

    @Test
    void collectsTextToolCallsFinishReasonAndUsage() {
        OpenAiChatCompletionEncoder encoder = new OpenAiChatCompletionEncoder("anthropic/claude-sonnet");
        encoder.accept(new CompletionEvent.Started("msg_1", "claude-sonnet", 123));
        encoder.accept(new CompletionEvent.BlockStarted(0, BlockType.TEXT, null, null));
        encoder.accept(new CompletionEvent.TextDelta(0, "Hello"));
        encoder.accept(new CompletionEvent.BlockFinished(0));
        encoder.accept(new CompletionEvent.BlockStarted(1, BlockType.TOOL_CALL, "tool_1", "lookup"));
        encoder.accept(new CompletionEvent.ToolCallArgumentsDelta(1, "{\"q\":"));
        encoder.accept(new CompletionEvent.ToolCallArgumentsDelta(1, "\"x\"}"));
        encoder.accept(new CompletionEvent.BlockFinished(1));
        encoder.accept(new CompletionEvent.UsageSnapshot(12, 4, 2, 3));
        encoder.accept(new CompletionEvent.Finished(FinishReason.TOOL_CALLS));

        ObjectNode result = encoder.completion();
        assertEquals("anthropic/claude-sonnet", result.path("model").asText());
        assertEquals("Hello", result.at("/choices/0/message/content").asText());
        assertEquals("tool_1", result.at("/choices/0/message/tool_calls/0/id").asText());
        assertEquals("{\"q\":\"x\"}", result.at("/choices/0/message/tool_calls/0/function/arguments").asText());
        assertEquals("tool_calls", result.at("/choices/0/finish_reason").asText());
        assertEquals(12, result.at("/usage/prompt_tokens").asInt());
        assertEquals(16, result.at("/usage/total_tokens").asInt());
        assertEquals(3, result.at("/usage/prompt_tokens_details/cached_tokens").asInt());
    }

    @Test
    void emitsRoleContentToolFinishAndUsageChunksOnce() {
        OpenAiChatCompletionEncoder encoder = new OpenAiChatCompletionEncoder("claude-sonnet");
        assertEquals("assistant", encoder.accept(
                new CompletionEvent.Started("msg_2", "claude-upstream", 456))
                .getFirst().at("/choices/0/delta/role").asText());
        encoder.accept(new CompletionEvent.BlockStarted(3, BlockType.TOOL_CALL, "tool_2", "search"));
        List<ObjectNode> args = encoder.accept(new CompletionEvent.ToolCallArgumentsDelta(3, "{}"));
        assertEquals("{}", args.getFirst().at("/choices/0/delta/tool_calls/0/function/arguments").asText());
        encoder.accept(new CompletionEvent.UsageSnapshot(7, 2, 0, 1));
        List<ObjectNode> terminal = encoder.accept(new CompletionEvent.Finished(FinishReason.STOP));
        assertEquals(2, terminal.size());
        assertEquals("tool_calls", terminal.getFirst().at("/choices/0/finish_reason").asText());
        assertTrue(terminal.get(1).path("choices").isEmpty());
        assertTrue(encoder.accept(new CompletionEvent.Finished(FinishReason.STOP)).isEmpty());
    }
}
