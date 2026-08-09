package com.aiproxyoauth.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

public record ChatRequest(
        String model,
        List<Message> messages,
        List<ToolDefinition> tools,
        ToolChoice toolChoice,
        int maxOutputTokens,
        Double temperature,
        Double topP,
        List<String> stopSequences,
        String reasoningEffort,
        boolean stream
) {
    public ChatRequest {
        model = requireNonBlank(model, "model");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        toolChoice = Objects.requireNonNull(toolChoice, "toolChoice");
        stopSequences = List.copyOf(Objects.requireNonNull(stopSequences, "stopSequences"));
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        requireRange(temperature, 0.0, 2.0, "temperature");
        requireRange(topP, 0.0, 1.0, "topP");
        if (reasoningEffort != null && reasoningEffort.isBlank()) {
            reasoningEffort = null;
        }
        for (String stop : stopSequences) {
            requireNonBlank(stop, "stopSequence");
        }
    }

    public enum Role {
        SYSTEM,
        DEVELOPER,
        USER,
        ASSISTANT,
        TOOL
    }

    public record Message(Role role, List<Content> content) {
        public Message {
            role = Objects.requireNonNull(role, "role");
            content = List.copyOf(Objects.requireNonNull(content, "content"));
        }
    }

    public sealed interface Content permits Text, Image, ToolCall, ToolResult, Reasoning {}

    public record Text(String text) implements Content {
        public Text {
            text = Objects.requireNonNull(text, "text");
        }
    }

    public record Image(String mediaType, byte[] data) implements Content {
        public Image {
            mediaType = requireNonBlank(mediaType, "mediaType");
            if (!mediaType.startsWith("image/")) {
                throw new IllegalArgumentException("mediaType must be an image type");
            }
            data = Objects.requireNonNull(data, "data").clone();
            if (data.length == 0) {
                throw new IllegalArgumentException("image data cannot be empty");
            }
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    public record ToolCall(String id, String name, String argumentsJson) implements Content {
        public ToolCall {
            id = requireNonBlank(id, "id");
            name = requireNonBlank(name, "name");
            argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
        }
    }

    public record ToolResult(String toolCallId, String output, boolean error) implements Content {
        public ToolResult {
            toolCallId = requireNonBlank(toolCallId, "toolCallId");
            output = Objects.requireNonNull(output, "output");
        }
    }

    public record Reasoning(
            String text,
            String signature,
            JsonNode redactedData
    ) implements Content {
        public Reasoning {
            text = Objects.requireNonNull(text, "text");
            signature = Objects.requireNonNull(signature, "signature");
            redactedData = redactedData == null ? null : redactedData.deepCopy();
        }

        @Override
        public JsonNode redactedData() {
            return redactedData == null ? null : redactedData.deepCopy();
        }
    }

    public record ToolDefinition(
            String name,
            String description,
            JsonNode inputSchema
    ) {
        public ToolDefinition {
            name = requireNonBlank(name, "name");
            description = description == null ? "" : description;
            inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
        }

        @Override
        public JsonNode inputSchema() {
            return inputSchema.deepCopy();
        }
    }

    public sealed interface ToolChoice permits
            ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, ToolChoice.Named {

        record Auto() implements ToolChoice {}

        record None() implements ToolChoice {}

        record Required() implements ToolChoice {}

        record Named(String name) implements ToolChoice {
            public Named {
                name = requireNonBlank(name, "name");
            }
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static void requireRange(Double value, double minimum, double maximum, String name) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum
            );
        }
    }
}
