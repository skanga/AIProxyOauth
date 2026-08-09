package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.stream.BlockType;
import com.aiproxyoauth.provider.stream.CompletionEvent;
import com.aiproxyoauth.provider.stream.FinishReason;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResponsesEventEncoder {
    private final String requestedModel;
    private final Map<Integer, OutputBlock> blocks = new LinkedHashMap<>();
    private CompletionEvent.UsageSnapshot usage = new CompletionEvent.UsageSnapshot(0, 0, 0, 0);
    private String id;
    private long createdAt;
    private FinishReason finishReason;
    private long sequenceNumber;
    private boolean terminalEmitted;

    public ResponsesEventEncoder(String requestedModel) {
        this.requestedModel = Objects.requireNonNull(requestedModel, "requestedModel");
    }

    public List<StreamEvent> accept(CompletionEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof CompletionEvent.Started started) {
            if (id != null) return List.of();
            id = started.id().startsWith("resp_") ? started.id() : "resp_" + started.id();
            createdAt = started.createdEpochSeconds();
            return List.of(responseEvent("response.created", responseNode("in_progress")));
        }
        if (event instanceof CompletionEvent.BlockStarted started) return startBlock(started);
        if (event instanceof CompletionEvent.TextDelta delta) return textDelta(delta);
        if (event instanceof CompletionEvent.RefusalDelta delta) return refusalDelta(delta);
        if (event instanceof CompletionEvent.ReasoningDelta delta) return reasoningDelta(delta);
        if (event instanceof CompletionEvent.ReasoningSignature signature) {
            OutputBlock block = blocks.get(signature.index());
            if (block != null) block.signature.append(signature.signature());
            return List.of();
        }
        if (event instanceof CompletionEvent.RedactedReasoning redacted) {
            OutputBlock block = blocks.get(redacted.index());
            if (block != null) block.redactedData = redacted.data();
            return List.of();
        }
        if (event instanceof CompletionEvent.ToolCallArgumentsDelta delta) return toolDelta(delta);
        if (event instanceof CompletionEvent.BlockFinished finished) return finishBlock(finished.index());
        if (event instanceof CompletionEvent.UsageSnapshot snapshot) {
            usage = snapshot;
            return List.of();
        }
        if (event instanceof CompletionEvent.Finished finished) {
            if (finishReason == null) finishReason = finished.reason();
            if (terminalEmitted) return List.of();
            terminalEmitted = true;
            String name = finishReason == FinishReason.LENGTH
                    ? "response.incomplete" : "response.completed";
            return List.of(responseEvent(name, response()));
        }
        return List.of();
    }

    public ObjectNode response() {
        if (id == null || finishReason == null) {
            throw new IllegalStateException("Response is not terminal");
        }
        return responseNode(finishReason == FinishReason.LENGTH ? "incomplete" : "completed");
    }

    public CompletionEvent.UsageSnapshot usage() {
        return usage;
    }

    public boolean isFinished() {
        return finishReason != null;
    }

    private List<StreamEvent> startBlock(CompletionEvent.BlockStarted started) {
        int outputIndex = blocks.size();
        OutputBlock block = new OutputBlock(
                outputIndex, started.type(), itemId(started.type(), started.index()),
                started.id(), started.name());
        blocks.put(started.index(), block);
        ObjectNode item = block.item(false);
        List<StreamEvent> events = new ArrayList<>();
        events.add(streamEvent("response.output_item.added", withOutputItem(item, outputIndex)));
        if (started.type() == BlockType.TEXT || started.type() == BlockType.REFUSAL) {
            ObjectNode part = block.contentPart();
            ObjectNode data = baseIndexed(block);
            data.put("content_index", 0);
            data.set("part", part);
            events.add(streamEvent("response.content_part.added", data));
        }
        return List.copyOf(events);
    }

    private List<StreamEvent> textDelta(CompletionEvent.TextDelta delta) {
        OutputBlock block = requiredBlock(delta.index(), BlockType.TEXT);
        block.text.append(delta.text());
        ObjectNode data = baseIndexed(block);
        data.put("content_index", 0);
        data.put("delta", delta.text());
        return delta.text().isEmpty()
                ? List.of() : List.of(streamEvent("response.output_text.delta", data));
    }

    private List<StreamEvent> refusalDelta(CompletionEvent.RefusalDelta delta) {
        OutputBlock block = requiredBlock(delta.index(), BlockType.REFUSAL);
        block.text.append(delta.refusal());
        ObjectNode data = baseIndexed(block);
        data.put("content_index", 0);
        data.put("delta", delta.refusal());
        return delta.refusal().isEmpty()
                ? List.of() : List.of(streamEvent("response.refusal.delta", data));
    }

    private List<StreamEvent> reasoningDelta(CompletionEvent.ReasoningDelta delta) {
        OutputBlock block = requiredBlock(delta.index(), BlockType.REASONING);
        block.text.append(delta.text());
        ObjectNode data = baseIndexed(block);
        data.put("content_index", 0);
        data.put("delta", delta.text());
        return delta.text().isEmpty()
                ? List.of() : List.of(streamEvent("response.reasoning_text.delta", data));
    }

    private List<StreamEvent> toolDelta(CompletionEvent.ToolCallArgumentsDelta delta) {
        OutputBlock block = requiredBlock(delta.index(), BlockType.TOOL_CALL);
        block.arguments.append(delta.json());
        ObjectNode data = baseIndexed(block);
        data.put("delta", delta.json());
        return delta.json().isEmpty()
                ? List.of() : List.of(streamEvent("response.function_call_arguments.delta", data));
    }

    private List<StreamEvent> finishBlock(int index) {
        OutputBlock block = blocks.get(index);
        if (block == null || block.finished) return List.of();
        block.finished = true;
        List<StreamEvent> events = new ArrayList<>();
        if (block.type == BlockType.TEXT) {
            ObjectNode done = baseIndexed(block);
            done.put("content_index", 0);
            done.put("text", block.text.toString());
            events.add(streamEvent("response.output_text.done", done));
            events.add(contentPartDone(block));
        } else if (block.type == BlockType.REFUSAL) {
            ObjectNode done = baseIndexed(block);
            done.put("content_index", 0);
            done.put("refusal", block.text.toString());
            events.add(streamEvent("response.refusal.done", done));
            events.add(contentPartDone(block));
        } else if (block.type == BlockType.REASONING) {
            ObjectNode done = baseIndexed(block);
            done.put("content_index", 0);
            done.put("text", block.text.toString());
            events.add(streamEvent("response.reasoning_text.done", done));
        } else if (block.type == BlockType.TOOL_CALL) {
            ObjectNode done = baseIndexed(block);
            done.put("arguments", block.arguments.toString());
            events.add(streamEvent("response.function_call_arguments.done", done));
        }
        events.add(streamEvent("response.output_item.done",
                withOutputItem(block.item(true), block.outputIndex)));
        return List.copyOf(events);
    }

    private StreamEvent contentPartDone(OutputBlock block) {
        ObjectNode data = baseIndexed(block);
        data.put("content_index", 0);
        data.set("part", block.contentPart());
        return streamEvent("response.content_part.done", data);
    }

    private ObjectNode responseNode(String status) {
        requireStarted();
        ObjectNode response = Json.MAPPER.createObjectNode();
        response.put("id", id);
        response.put("object", "response");
        response.put("created_at", createdAt);
        response.put("status", status);
        response.put("model", requestedModel);
        ArrayNode output = response.putArray("output");
        blocks.values().forEach(block -> output.add(block.item(block.finished)));
        if ("incomplete".equals(status)) {
            response.putObject("incomplete_details").put("reason", "max_output_tokens");
        } else {
            response.putNull("incomplete_details");
        }
        response.set("usage", usageNode());
        return response;
    }

    private ObjectNode usageNode() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("input_tokens", usage.inputTokens());
        node.put("output_tokens", usage.outputTokens());
        node.put("total_tokens", usage.inputTokens() + usage.outputTokens());
        ObjectNode inputDetails = node.putObject("input_tokens_details");
        inputDetails.put("cached_tokens", usage.cacheReadInputTokens());
        ObjectNode anthropic = node.putObject("anthropic");
        anthropic.put("cache_creation_input_tokens", usage.cacheCreationInputTokens());
        return node;
    }

    private ObjectNode baseIndexed(OutputBlock block) {
        ObjectNode data = Json.MAPPER.createObjectNode();
        data.put("item_id", block.itemId);
        data.put("output_index", block.outputIndex);
        return data;
    }

    private ObjectNode withOutputItem(ObjectNode item, int outputIndex) {
        ObjectNode data = Json.MAPPER.createObjectNode();
        data.put("output_index", outputIndex);
        data.set("item", item);
        return data;
    }

    private StreamEvent streamEvent(String name, ObjectNode data) {
        ObjectNode payload = data.deepCopy();
        payload.put("type", name);
        payload.put("sequence_number", sequenceNumber++);
        return new StreamEvent(name, payload);
    }

    private StreamEvent responseEvent(String name, ObjectNode response) {
        ObjectNode data = Json.MAPPER.createObjectNode();
        data.set("response", response);
        return streamEvent(name, data);
    }

    private OutputBlock requiredBlock(int index, BlockType expected) {
        OutputBlock block = blocks.get(index);
        if (block == null || block.type != expected) {
            throw new IllegalStateException("Canonical event referenced an incompatible block");
        }
        return block;
    }

    private String itemId(BlockType type, int index) {
        requireStarted();
        String prefix = switch (type) {
            case TOOL_CALL -> "fc_";
            case REASONING, REDACTED_REASONING -> "rs_";
            case TEXT, REFUSAL -> "msg_";
        };
        return prefix + id.replaceFirst("^resp_", "") + "_" + index;
    }

    private void requireStarted() {
        if (id == null) throw new IllegalStateException("Response has not started");
    }

    public record StreamEvent(String name, ObjectNode data) {
        public StreamEvent {
            name = Objects.requireNonNull(name, "name");
            data = Objects.requireNonNull(data, "data").deepCopy();
        }

        @Override
        public ObjectNode data() {
            return data.deepCopy();
        }
    }

    private static final class OutputBlock {
        private final int outputIndex;
        private final BlockType type;
        private final String itemId;
        private final String callId;
        private final String name;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
        private JsonNode redactedData;
        private boolean finished;

        private OutputBlock(int outputIndex, BlockType type,
                            String itemId, String callId, String name) {
            this.outputIndex = outputIndex;
            this.type = type;
            this.itemId = itemId;
            this.callId = callId;
            this.name = name;
        }

        private ObjectNode item(boolean complete) {
            ObjectNode item = Json.MAPPER.createObjectNode();
            item.put("id", itemId);
            switch (type) {
                case TEXT, REFUSAL -> {
                    item.put("type", "message");
                    item.put("role", "assistant");
                    item.put("status", complete ? "completed" : "in_progress");
                    item.putArray("content").add(contentPart());
                }
                case TOOL_CALL -> {
                    item.put("type", "function_call");
                    item.put("status", complete ? "completed" : "in_progress");
                    item.put("call_id", callId);
                    item.put("name", name);
                    item.put("arguments", arguments.toString());
                }
                case REASONING, REDACTED_REASONING -> {
                    item.put("type", "reasoning");
                    item.putArray("summary");
                    ArrayNode content = item.putArray("content");
                    if (!text.isEmpty()) {
                        content.addObject().put("type", "reasoning_text").put("text", text.toString());
                    }
                    if (!signature.isEmpty()) item.put("reasoning_signature", signature.toString());
                    if (redactedData != null) item.set("redacted_data", redactedData.deepCopy());
                }
            }
            return item;
        }

        private ObjectNode contentPart() {
            ObjectNode part = Json.MAPPER.createObjectNode();
            if (type == BlockType.REFUSAL) {
                part.put("type", "refusal");
                part.put("refusal", text.toString());
            } else {
                part.put("type", "output_text");
                part.put("text", text.toString());
                part.putArray("annotations");
            }
            return part;
        }
    }
}
