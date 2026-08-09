package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.ProviderError;
import com.aiproxyoauth.provider.stream.BlockType;
import com.aiproxyoauth.provider.stream.CompletionEvent;
import com.aiproxyoauth.provider.stream.CompletionStreamDecoder;
import com.aiproxyoauth.provider.stream.FinishReason;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AnthropicStreamDecoder implements CompletionStreamDecoder {
    private static final Set<String> KNOWN_EVENTS = Set.of(
            "message_start",
            "content_block_start",
            "content_block_delta",
            "content_block_stop",
            "message_delta",
            "message_stop",
            "error"
    );

    private final Clock clock;
    private final IncrementalSseFramer framer;
    private final Map<Integer, BlockType> openBlocks = new HashMap<>();
    private final Set<Integer> ignoredBlocks = new HashSet<>();
    private boolean terminal;
    private boolean started;
    private FinishReason finishReason = FinishReason.UNSPECIFIED;
    private long inputTokens;
    private long outputTokens;
    private long cacheCreationInputTokens;
    private long cacheReadInputTokens;

    public AnthropicStreamDecoder(Clock clock) {
        this(clock, IncrementalSseFramer.DEFAULT_MAX_EVENT_BYTES);
    }

    public AnthropicStreamDecoder(Clock clock, int maximumEventBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.framer = new IncrementalSseFramer(maximumEventBytes);
    }

    @Override
    public List<CompletionEvent> feed(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (terminal || bytes.length == 0) {
            return List.of();
        }
        List<CompletionEvent> events = new ArrayList<>();
        try {
            framer.feed(bytes, frame -> consume(frame, events));
        } catch (IncrementalSseFramer.FrameLimitException error) {
            failProtocol(events, "Anthropic SSE frame exceeded the size limit");
        } catch (IncrementalSseFramer.FrameFormatException error) {
            failProtocol(events, "Anthropic SSE stream contained invalid UTF-8");
        }
        return List.copyOf(events);
    }

    @Override
    public List<CompletionEvent> end() {
        if (terminal) {
            return List.of();
        }
        List<CompletionEvent> events = new ArrayList<>();
        failProtocol(
                events,
                framer.hasPendingData()
                        ? "Anthropic SSE stream ended in a partial frame"
                        : "Anthropic SSE stream ended before message_stop"
        );
        return List.copyOf(events);
    }

    private void consume(
            IncrementalSseFramer.Event frame,
            List<CompletionEvent> events
    ) {
        if (terminal || frame.data().isEmpty() || "[DONE]".equals(frame.data())) {
            return;
        }
        if ("ping".equals(frame.name())) {
            events.add(new CompletionEvent.Heartbeat());
            return;
        }
        if (!KNOWN_EVENTS.contains(frame.name())) {
            return;
        }

        JsonNode root;
        try {
            root = Json.MAPPER.readTree(frame.data());
        } catch (Exception error) {
            failProtocol(events, "Anthropic returned malformed SSE event JSON");
            return;
        }
        if (root == null || !root.isObject()) {
            failProtocol(events, "Anthropic returned an invalid SSE event");
            return;
        }

        try {
            switch (frame.name()) {
                case "message_start" -> messageStart(root, events);
                case "content_block_start" -> blockStart(root, events);
                case "content_block_delta" -> blockDelta(root, events);
                case "content_block_stop" -> blockStop(root, events);
                case "message_delta" -> messageDelta(root, events);
                case "message_stop" -> messageStop(events);
                case "error" -> upstreamError(root, events);
                default -> {
                }
            }
        } catch (ProtocolViolation error) {
            failProtocol(events, error.getMessage());
        }
    }

    private void messageStart(JsonNode root, List<CompletionEvent> events)
            throws ProtocolViolation {
        if (started) {
            throw violation("Anthropic sent message_start more than once");
        }
        JsonNode message = root.path("message");
        String id = requiredText(message, "id");
        String model = requiredText(message, "model");
        events.add(new CompletionEvent.Started(
                id, model, clock.instant().getEpochSecond()));
        started = true;
        addUsage(message.path("usage"), events);
    }

    private void blockStart(JsonNode root, List<CompletionEvent> events)
            throws ProtocolViolation {
        int index = requiredIndex(root);
        requireStarted();
        if (openBlocks.containsKey(index) || ignoredBlocks.contains(index)) {
            throw violation("Anthropic started the same content block twice");
        }
        JsonNode block = root.path("content_block");
        String type = requiredText(block, "type");
        switch (type) {
            case "text" -> start(index, BlockType.TEXT, null, null, events);
            case "thinking" -> start(index, BlockType.REASONING, null, null, events);
            case "redacted_thinking" -> {
                start(index, BlockType.REDACTED_REASONING, null, null, events);
                JsonNode data = block.get("data");
                if (data == null || data.isNull()) {
                    throw violation("Anthropic redacted thinking block has no data");
                }
                events.add(new CompletionEvent.RedactedReasoning(index, data));
            }
            case "tool_use" -> start(
                    index,
                    BlockType.TOOL_CALL,
                    requiredText(block, "id"),
                    requiredText(block, "name"),
                    events
            );
            default -> ignoredBlocks.add(index);
        }
    }

    private void start(
            int index,
            BlockType type,
            String id,
            String name,
            List<CompletionEvent> events
    ) {
        openBlocks.put(index, type);
        events.add(new CompletionEvent.BlockStarted(index, type, id, name));
    }

    private void blockDelta(JsonNode root, List<CompletionEvent> events)
            throws ProtocolViolation {
        int index = requiredIndex(root);
        requireStarted();
        if (ignoredBlocks.contains(index)) {
            return;
        }
        BlockType openType = openBlocks.get(index);
        if (openType == null) {
            throw violation("Anthropic sent a delta for an unopened content block");
        }
        JsonNode delta = root.path("delta");
        String type = requiredText(delta, "type");
        switch (type) {
            case "text_delta" -> {
                requireBlock(openType, BlockType.TEXT);
                events.add(new CompletionEvent.TextDelta(
                        index, requiredTextAllowEmpty(delta, "text")));
            }
            case "thinking_delta" -> {
                requireBlock(openType, BlockType.REASONING);
                events.add(new CompletionEvent.ReasoningDelta(
                        index, requiredTextAllowEmpty(delta, "thinking")));
            }
            case "signature_delta" -> {
                requireBlock(openType, BlockType.REASONING);
                events.add(new CompletionEvent.ReasoningSignature(
                        index, requiredTextAllowEmpty(delta, "signature")));
            }
            case "input_json_delta" -> {
                requireBlock(openType, BlockType.TOOL_CALL);
                events.add(new CompletionEvent.ToolCallArgumentsDelta(
                        index, requiredTextAllowEmpty(delta, "partial_json")));
            }
            default -> {
                // Future deltas within a known block are safe to ignore.
            }
        }
    }

    private void blockStop(JsonNode root, List<CompletionEvent> events)
            throws ProtocolViolation {
        int index = requiredIndex(root);
        requireStarted();
        if (ignoredBlocks.remove(index)) {
            return;
        }
        if (openBlocks.remove(index) == null) {
            throw violation("Anthropic stopped an unopened content block");
        }
        events.add(new CompletionEvent.BlockFinished(index));
    }

    private void messageDelta(JsonNode root, List<CompletionEvent> events)
            throws ProtocolViolation {
        requireStarted();
        JsonNode reason = root.path("delta").path("stop_reason");
        if (reason.isTextual()) {
            finishReason = mapFinishReason(reason.asText());
        }
        addUsage(root.path("usage"), events);
    }

    private void messageStop(List<CompletionEvent> events) throws ProtocolViolation {
        requireStarted();
        if (!openBlocks.isEmpty() || !ignoredBlocks.isEmpty()) {
            throw violation("Anthropic stopped the message with an open content block");
        }
        events.add(new CompletionEvent.Finished(finishReason));
        terminal = true;
    }

    private void upstreamError(JsonNode root, List<CompletionEvent> events) {
        JsonNode error = root.path("error");
        String type = error.path("type").asText();
        int status = switch (type) {
            case "authentication_error" -> 401;
            case "permission_error" -> 403;
            case "invalid_request_error" -> 400;
            case "not_found_error" -> 404;
            case "request_too_large" -> 413;
            case "rate_limit_error" -> 429;
            case "overloaded_error" -> 529;
            default -> 502;
        };
        events.add(new CompletionEvent.Error(
                AnthropicErrorParser.parse(status, root.toString())));
        terminal = true;
    }

    private void addUsage(JsonNode usage, List<CompletionEvent> events)
            throws ProtocolViolation {
        if (!usage.isObject()) {
            return;
        }
        inputTokens = valueOrPrevious(usage, "input_tokens", inputTokens);
        outputTokens = valueOrPrevious(usage, "output_tokens", outputTokens);
        cacheCreationInputTokens = valueOrPrevious(
                usage, "cache_creation_input_tokens", cacheCreationInputTokens);
        cacheReadInputTokens = valueOrPrevious(
                usage, "cache_read_input_tokens", cacheReadInputTokens);
        events.add(new CompletionEvent.UsageSnapshot(
                inputTokens,
                outputTokens,
                cacheCreationInputTokens,
                cacheReadInputTokens
        ));
    }

    private void failProtocol(List<CompletionEvent> events, String message) {
        if (terminal) {
            return;
        }
        terminal = true;
        events.add(new CompletionEvent.Error(
                ProviderError.of(ProviderError.Kind.PROTOCOL, message)));
    }

    private static long valueOrPrevious(JsonNode node, String field, long previous)
            throws ProtocolViolation {
        JsonNode value = node.get(field);
        if (value == null) {
            return previous;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw violation("Anthropic SSE usage value is invalid");
        }
        return value.asLong();
    }

    private void requireStarted() throws ProtocolViolation {
        if (!started) {
            throw violation("Anthropic content event arrived before message_start");
        }
    }

    private static void requireBlock(BlockType actual, BlockType expected)
            throws ProtocolViolation {
        if (actual != expected) {
            throw violation("Anthropic delta type did not match its content block");
        }
    }

    private static int requiredIndex(JsonNode root) throws ProtocolViolation {
        JsonNode index = root.get("index");
        if (index == null || !index.canConvertToInt() || index.asInt() < 0) {
            throw violation("Anthropic SSE event has an invalid content-block index");
        }
        return index.asInt();
    }

    private static String requiredText(JsonNode node, String field)
            throws ProtocolViolation {
        String value = requiredTextAllowEmpty(node, field);
        if (value.isBlank()) {
            throw violation("Anthropic SSE event is missing " + field);
        }
        return value;
    }

    private static String requiredTextAllowEmpty(JsonNode node, String field)
            throws ProtocolViolation {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw violation("Anthropic SSE event is missing " + field);
        }
        return value.asText();
    }

    private static FinishReason mapFinishReason(String reason) {
        return switch (reason) {
            case "end_turn", "stop_sequence" -> FinishReason.STOP;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.LENGTH;
            default -> FinishReason.UNSPECIFIED;
        };
    }

    private static ProtocolViolation violation(String message) {
        return new ProtocolViolation(message);
    }

    private static final class ProtocolViolation extends Exception {
        private ProtocolViolation(String message) {
            super(message);
        }
    }
}
