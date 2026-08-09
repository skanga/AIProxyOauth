package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.stream.BlockType;
import com.aiproxyoauth.provider.stream.CompletionEvent;
import com.aiproxyoauth.provider.stream.FinishReason;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OpenAiChatCompletionEncoder {
    private final String requestedModel;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, ToolCall> toolCalls = new LinkedHashMap<>();
    private String id;
    private long created;
    private CompletionEvent.UsageSnapshot usage = new CompletionEvent.UsageSnapshot(0, 0, 0, 0);
    private FinishReason finishReason;
    private boolean terminalChunksEmitted;

    public OpenAiChatCompletionEncoder(String requestedModel) {
        this.requestedModel = Objects.requireNonNull(requestedModel, "requestedModel");
    }

    public List<ObjectNode> accept(CompletionEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof CompletionEvent.Started started) {
            if (id != null) return List.of();
            id = started.id().startsWith("chatcmpl-")
                    ? started.id() : "chatcmpl-" + started.id();
            created = started.createdEpochSeconds();
            return List.of(chunk(roleDelta(), null));
        }
        if (event instanceof CompletionEvent.TextDelta delta) {
            content.append(delta.text());
            return delta.text().isEmpty() ? List.of() : List.of(chunk(textDelta(delta.text()), null));
        }
        if (event instanceof CompletionEvent.RefusalDelta delta) {
            content.append(delta.refusal());
            ObjectNode payload = Json.MAPPER.createObjectNode();
            payload.put("refusal", delta.refusal());
            return List.of(chunk(payload, null));
        }
        if (event instanceof CompletionEvent.ReasoningDelta delta) {
            reasoning.append(delta.text());
            ObjectNode payload = Json.MAPPER.createObjectNode();
            payload.put("reasoning_content", delta.text());
            return delta.text().isEmpty() ? List.of() : List.of(chunk(payload, null));
        }
        if (event instanceof CompletionEvent.BlockStarted started
                && started.type() == BlockType.TOOL_CALL) {
            int toolIndex = toolCalls.size();
            ToolCall call = new ToolCall(toolIndex, started.id(), started.name(), new StringBuilder());
            toolCalls.put(started.index(), call);
            return List.of(chunk(toolStartDelta(call), null));
        }
        if (event instanceof CompletionEvent.ToolCallArgumentsDelta delta) {
            ToolCall call = toolCalls.get(delta.index());
            if (call == null || delta.json().isEmpty()) return List.of();
            call.arguments().append(delta.json());
            return List.of(chunk(toolArgumentsDelta(call.index(), delta.json()), null));
        }
        if (event instanceof CompletionEvent.UsageSnapshot snapshot) {
            usage = snapshot;
            return List.of();
        }
        if (event instanceof CompletionEvent.Finished finished) {
            if (finishReason == null) finishReason = finished.reason();
            if (terminalChunksEmitted) return List.of();
            terminalChunksEmitted = true;
            return List.of(chunk(Json.MAPPER.createObjectNode(), finishReason()), usageChunk());
        }
        return List.of();
    }

    public ObjectNode completion() {
        if (id == null || finishReason == null) {
            throw new IllegalStateException("Completion is not terminal");
        }
        ObjectNode root = envelope("chat.completion");
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (content.isEmpty()) message.putNull("content"); else message.put("content", content.toString());
        if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
        if (!toolCalls.isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            toolCalls.values().forEach(call -> calls.add(fullToolCall(call)));
        }
        choice.put("finish_reason", finishReason());
        root.set("usage", usageNode());
        return root;
    }

    public CompletionEvent.UsageSnapshot usage() {
        return usage;
    }

    public boolean isFinished() {
        return finishReason != null;
    }

    private ObjectNode chunk(ObjectNode delta, String finish) {
        ObjectNode root = envelope("chat.completion.chunk");
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finish == null) choice.putNull("finish_reason"); else choice.put("finish_reason", finish);
        return root;
    }

    private ObjectNode usageChunk() {
        ObjectNode root = envelope("chat.completion.chunk");
        root.set("choices", Json.MAPPER.createArrayNode());
        root.set("usage", usageNode());
        return root;
    }

    private ObjectNode envelope(String object) {
        if (id == null) throw new IllegalStateException("Completion has not started");
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("id", id);
        root.put("object", object);
        root.put("created", created);
        root.put("model", requestedModel);
        return root;
    }

    private ObjectNode usageNode() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("prompt_tokens", usage.inputTokens());
        node.put("completion_tokens", usage.outputTokens());
        node.put("total_tokens", usage.inputTokens() + usage.outputTokens());
        if (usage.cacheReadInputTokens() > 0 || usage.cacheCreationInputTokens() > 0) {
            ObjectNode details = node.putObject("prompt_tokens_details");
            details.put("cached_tokens", usage.cacheReadInputTokens());
            details.put("cache_creation_tokens", usage.cacheCreationInputTokens());
        }
        return node;
    }

    private ObjectNode roleDelta() {
        ObjectNode delta = Json.MAPPER.createObjectNode();
        delta.put("role", "assistant");
        return delta;
    }

    private ObjectNode textDelta(String text) {
        ObjectNode delta = Json.MAPPER.createObjectNode();
        delta.put("content", text);
        return delta;
    }

    private ObjectNode toolStartDelta(ToolCall call) {
        ObjectNode delta = Json.MAPPER.createObjectNode();
        ArrayNode calls = delta.putArray("tool_calls");
        ObjectNode item = calls.addObject();
        item.put("index", call.index());
        item.put("id", call.id());
        item.put("type", "function");
        ObjectNode function = item.putObject("function");
        function.put("name", call.name());
        function.put("arguments", "");
        return delta;
    }

    private ObjectNode toolArgumentsDelta(int index, String arguments) {
        ObjectNode delta = Json.MAPPER.createObjectNode();
        ObjectNode item = delta.putArray("tool_calls").addObject();
        item.put("index", index);
        item.putObject("function").put("arguments", arguments);
        return delta;
    }

    private ObjectNode fullToolCall(ToolCall call) {
        ObjectNode item = Json.MAPPER.createObjectNode();
        item.put("id", call.id());
        item.put("type", "function");
        ObjectNode function = item.putObject("function");
        function.put("name", call.name());
        function.put("arguments", call.arguments().toString());
        return item;
    }

    private String finishReason() {
        return switch (finishReason) {
            case TOOL_CALLS -> "tool_calls";
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
            case STOP, UNSPECIFIED -> toolCalls.isEmpty() ? "stop" : "tool_calls";
        };
    }

    private record ToolCall(int index, String id, String name, StringBuilder arguments) {}
}
