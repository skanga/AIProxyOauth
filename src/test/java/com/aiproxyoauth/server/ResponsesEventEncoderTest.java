package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.stream.BlockType;
import com.aiproxyoauth.provider.stream.CompletionEvent;
import com.aiproxyoauth.provider.stream.FinishReason;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResponsesEventEncoderTest {

    @Test
    void collectsOrderedTextReasoningToolsAndUsage() {
        ResponsesEventEncoder encoder = new ResponsesEventEncoder("anthropic/sonnet");
        encoder.accept(new CompletionEvent.Started("msg_1", "claude-sonnet", 100));
        encoder.accept(new CompletionEvent.BlockStarted(0, BlockType.REASONING, null, null));
        encoder.accept(new CompletionEvent.ReasoningDelta(0, "plan"));
        encoder.accept(new CompletionEvent.BlockFinished(0));
        encoder.accept(new CompletionEvent.BlockStarted(1, BlockType.TEXT, null, null));
        encoder.accept(new CompletionEvent.TextDelta(1, "answer"));
        encoder.accept(new CompletionEvent.BlockFinished(1));
        encoder.accept(new CompletionEvent.BlockStarted(2, BlockType.TOOL_CALL, "call_1", "lookup"));
        encoder.accept(new CompletionEvent.ToolCallArgumentsDelta(2, "{}"));
        encoder.accept(new CompletionEvent.BlockFinished(2));
        encoder.accept(new CompletionEvent.UsageSnapshot(8, 5, 2, 3));
        encoder.accept(new CompletionEvent.Finished(FinishReason.TOOL_CALLS));

        ObjectNode response = encoder.response();
        assertEquals("completed", response.path("status").asText());
        assertEquals("reasoning", response.at("/output/0/type").asText());
        assertEquals("message", response.at("/output/1/type").asText());
        assertEquals("function_call", response.at("/output/2/type").asText());
        assertEquals("call_1", response.at("/output/2/call_id").asText());
        assertEquals(8, response.at("/usage/input_tokens").asInt());
        assertEquals(13, response.at("/usage/total_tokens").asInt());
        assertEquals(3, response.at("/usage/input_tokens_details/cached_tokens").asInt());
        assertEquals(2, response.at("/usage/anthropic/cache_creation_input_tokens").asInt());
    }

    @Test
    void emitsLifecycleEventsAndOneTerminalEvent() {
        ResponsesEventEncoder encoder = new ResponsesEventEncoder("claude-sonnet");
        ResponsesEventEncoder.StreamEvent created = encoder.accept(
                new CompletionEvent.Started("msg_2", "claude", 101)).getFirst();
        assertEquals("response.created", created.name());
        assertEquals("in_progress", created.data().at("/response/status").asText());
        assertEquals(List.of("response.output_item.added", "response.content_part.added"),
                encoder.accept(new CompletionEvent.BlockStarted(0, BlockType.TEXT, null, null))
                        .stream().map(ResponsesEventEncoder.StreamEvent::name).toList());
        assertEquals("response.output_text.delta", encoder.accept(
                new CompletionEvent.TextDelta(0, "hi")).getFirst().name());
        encoder.accept(new CompletionEvent.BlockFinished(0));
        List<ResponsesEventEncoder.StreamEvent> terminal = encoder.accept(
                new CompletionEvent.Finished(FinishReason.STOP));
        assertEquals(1, terminal.size());
        assertEquals("response.completed", terminal.getFirst().name());
        assertEquals("completed", terminal.getFirst().data()
                .at("/response/status").asText());
        assertTrue(encoder.accept(new CompletionEvent.Finished(FinishReason.STOP)).isEmpty());
    }

    @Test
    void lengthFinishProducesIncompleteResponse() {
        ResponsesEventEncoder encoder = new ResponsesEventEncoder("claude-sonnet");
        encoder.accept(new CompletionEvent.Started("msg_3", "claude", 102));
        List<ResponsesEventEncoder.StreamEvent> terminal = encoder.accept(
                new CompletionEvent.Finished(FinishReason.LENGTH));
        assertEquals("response.incomplete", terminal.getFirst().name());
        assertEquals("max_output_tokens",
                encoder.response().at("/incomplete_details/reason").asText());
    }

    @Test
    void usageTotalsMatchChatEncoderForEquivalentEvents() {
        ResponsesEventEncoder responses = new ResponsesEventEncoder("claude-sonnet");
        OpenAiChatCompletionEncoder chat = new OpenAiChatCompletionEncoder("claude-sonnet");
        List<CompletionEvent> events = List.of(
                new CompletionEvent.Started("msg_4", "claude", 103),
                new CompletionEvent.UsageSnapshot(21, 8, 4, 5),
                new CompletionEvent.Finished(FinishReason.STOP));
        events.forEach(event -> {
            responses.accept(event);
            chat.accept(event);
        });

        assertEquals(chat.completion().at("/usage/prompt_tokens").asLong(),
                responses.response().at("/usage/input_tokens").asLong());
        assertEquals(chat.completion().at("/usage/completion_tokens").asLong(),
                responses.response().at("/usage/output_tokens").asLong());
        assertEquals(chat.completion().at("/usage/total_tokens").asLong(),
                responses.response().at("/usage/total_tokens").asLong());
    }
}
