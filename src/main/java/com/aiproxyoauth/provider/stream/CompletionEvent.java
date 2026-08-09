package com.aiproxyoauth.provider.stream;

import com.aiproxyoauth.provider.ProviderError;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public sealed interface CompletionEvent permits
        CompletionEvent.Started,
        CompletionEvent.BlockStarted,
        CompletionEvent.TextDelta,
        CompletionEvent.RefusalDelta,
        CompletionEvent.ReasoningDelta,
        CompletionEvent.ReasoningSignature,
        CompletionEvent.RedactedReasoning,
        CompletionEvent.ToolCallArgumentsDelta,
        CompletionEvent.BlockFinished,
        CompletionEvent.UsageSnapshot,
        CompletionEvent.Finished,
        CompletionEvent.Error,
        CompletionEvent.Heartbeat {

    record Started(String id, String model, long createdEpochSeconds) implements CompletionEvent {
        public Started {
            id = requireNonBlank(id, "id");
            model = requireNonBlank(model, "model");
            if (createdEpochSeconds < 0) {
                throw new IllegalArgumentException("createdEpochSeconds cannot be negative");
            }
        }
    }

    record BlockStarted(
            int index,
            BlockType type,
            String id,
            String name
    ) implements CompletionEvent {
        public BlockStarted {
            requireIndex(index);
            type = Objects.requireNonNull(type, "type");
            id = blankToNull(id);
            name = blankToNull(name);
            if (type == BlockType.TOOL_CALL && (id == null || name == null)) {
                throw new IllegalArgumentException("tool-call blocks require id and name");
            }
        }
    }

    record TextDelta(int index, String text) implements CompletionEvent {
        public TextDelta {
            requireIndex(index);
            text = Objects.requireNonNull(text, "text");
        }
    }

    record RefusalDelta(int index, String refusal) implements CompletionEvent {
        public RefusalDelta {
            requireIndex(index);
            refusal = Objects.requireNonNull(refusal, "refusal");
        }
    }

    record ReasoningDelta(int index, String text) implements CompletionEvent {
        public ReasoningDelta {
            requireIndex(index);
            text = Objects.requireNonNull(text, "text");
        }
    }

    record ReasoningSignature(int index, String signature) implements CompletionEvent {
        public ReasoningSignature {
            requireIndex(index);
            signature = Objects.requireNonNull(signature, "signature");
        }
    }

    record RedactedReasoning(int index, JsonNode data) implements CompletionEvent {
        public RedactedReasoning {
            requireIndex(index);
            data = Objects.requireNonNull(data, "data").deepCopy();
        }

        @Override
        public JsonNode data() {
            return data.deepCopy();
        }
    }

    record ToolCallArgumentsDelta(int index, String json) implements CompletionEvent {
        public ToolCallArgumentsDelta {
            requireIndex(index);
            json = Objects.requireNonNull(json, "json");
        }
    }

    record BlockFinished(int index) implements CompletionEvent {
        public BlockFinished {
            requireIndex(index);
        }
    }

    /**
     * Cumulative usage totals observed so far. Consumers replace prior snapshots rather than
     * summing them and record usage once after terminal success.
     */
    record UsageSnapshot(
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens
    ) implements CompletionEvent {
        public UsageSnapshot {
            requireNonNegative(inputTokens, "inputTokens");
            requireNonNegative(outputTokens, "outputTokens");
            requireNonNegative(cacheCreationInputTokens, "cacheCreationInputTokens");
            requireNonNegative(cacheReadInputTokens, "cacheReadInputTokens");
        }
    }

    record Finished(FinishReason reason) implements CompletionEvent {
        public Finished {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    record Error(ProviderError error) implements CompletionEvent {
        public Error {
            error = Objects.requireNonNull(error, "error");
        }
    }

    record Heartbeat() implements CompletionEvent {}

    private static void requireIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("block index cannot be negative");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
