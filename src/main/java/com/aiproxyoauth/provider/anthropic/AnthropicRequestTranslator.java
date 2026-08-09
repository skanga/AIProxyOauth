package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class AnthropicRequestTranslator {
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");
    private static final int MAX_IMAGE_COUNT = 20;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_STOP_SEQUENCES = 4;
    private static final Set<String> REASONING_EFFORTS =
            Set.of("low", "medium", "high", "max");

    private final AnthropicCompatibilityProfile profile;

    public AnthropicRequestTranslator(AnthropicCompatibilityProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public ObjectNode translate(ChatRequest request) throws AnthropicTranslationException {
        Objects.requireNonNull(request, "request");
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxOutputTokens());
        root.put("stream", true);
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            root.put("top_p", request.topP());
        }
        if (!request.stopSequences().isEmpty()) {
            if (request.stopSequences().size() > MAX_STOP_SEQUENCES) {
                throw invalid("Anthropic supports at most four stop sequences");
            }
            ArrayNode stops = root.putArray("stop_sequences");
            request.stopSequences().forEach(stops::add);
        }

        root.set("system", systemBlocks(request));
        root.set("messages", messageBlocks(request));
        addTools(root, request);
        addReasoning(root, request.reasoningEffort());
        return root;
    }

    private ArrayNode systemBlocks(ChatRequest request) throws AnthropicTranslationException {
        ArrayNode system = Json.MAPPER.createArrayNode();
        system.addObject()
                .put("type", "text")
                .put("text", profile.oauthSystemPreamble());
        for (ChatRequest.Message message : request.messages()) {
            if (message.role() != ChatRequest.Role.SYSTEM
                    && message.role() != ChatRequest.Role.DEVELOPER) {
                continue;
            }
            for (ChatRequest.Content content : message.content()) {
                if (!(content instanceof ChatRequest.Text text)) {
                    throw invalid("System and developer messages must contain only text");
                }
                system.addObject().put("type", "text").put("text", text.text());
            }
        }
        return system;
    }

    private ArrayNode messageBlocks(ChatRequest request) throws AnthropicTranslationException {
        ArrayNode messages = Json.MAPPER.createArrayNode();
        Set<String> seenToolCalls = new HashSet<>();
        Set<String> outstandingToolCalls = new HashSet<>();
        ImageBudget imageBudget = new ImageBudget();

        for (ChatRequest.Message message : request.messages()) {
            if (message.role() == ChatRequest.Role.SYSTEM
                    || message.role() == ChatRequest.Role.DEVELOPER) {
                continue;
            }
            switch (message.role()) {
                case USER -> {
                    requireNoOutstanding(outstandingToolCalls);
                    appendMessage(
                            messages,
                            "user",
                            contentBlocks(message.content(), seenToolCalls,
                                    outstandingToolCalls, imageBudget, false)
                    );
                }
                case ASSISTANT -> {
                    requireNoOutstanding(outstandingToolCalls);
                    appendMessage(
                            messages,
                            "assistant",
                            contentBlocks(message.content(), seenToolCalls,
                                    outstandingToolCalls, imageBudget, true)
                    );
                }
                case TOOL -> appendMessage(
                        messages,
                        "user",
                        toolResultBlocks(message.content(), outstandingToolCalls)
                );
                default -> throw invalid("Unsupported message role");
            }
        }
        requireNoOutstanding(outstandingToolCalls);
        if (messages.isEmpty()) {
            throw invalid("At least one user or assistant message is required");
        }
        return messages;
    }

    private ArrayNode contentBlocks(
            List<ChatRequest.Content> content,
            Set<String> seenToolCalls,
            Set<String> outstandingToolCalls,
            ImageBudget imageBudget,
            boolean assistant
    ) throws AnthropicTranslationException {
        ArrayNode blocks = Json.MAPPER.createArrayNode();
        for (ChatRequest.Content item : content) {
            if (item instanceof ChatRequest.Text text) {
                blocks.addObject().put("type", "text").put("text", text.text());
            } else if (item instanceof ChatRequest.Image image) {
                if (assistant) {
                    throw invalid("Assistant messages cannot contain images");
                }
                blocks.add(imageBlock(image, imageBudget));
            } else if (item instanceof ChatRequest.ToolCall call) {
                if (!assistant) {
                    throw invalid("Tool calls must belong to assistant messages");
                }
                if (!seenToolCalls.add(call.id())) {
                    throw invalid("Duplicate tool-call id: " + call.id());
                }
                outstandingToolCalls.add(call.id());
                blocks.add(toolCallBlock(call));
            } else if (item instanceof ChatRequest.Reasoning reasoning) {
                if (!assistant) {
                    throw invalid("Reasoning blocks must belong to assistant messages");
                }
                addReasoningBlock(blocks, reasoning);
            } else if (item instanceof ChatRequest.ToolResult) {
                throw invalid("Tool results must belong to tool messages");
            }
        }
        if (blocks.isEmpty()) {
            throw invalid("Messages cannot have empty content");
        }
        return blocks;
    }

    private ArrayNode toolResultBlocks(
            List<ChatRequest.Content> content,
            Set<String> outstandingToolCalls
    ) throws AnthropicTranslationException {
        ArrayNode blocks = Json.MAPPER.createArrayNode();
        for (ChatRequest.Content item : content) {
            if (!(item instanceof ChatRequest.ToolResult result)) {
                throw invalid("Tool messages may contain only tool results");
            }
            if (!outstandingToolCalls.remove(result.toolCallId())) {
                throw invalid("Orphan tool result: " + result.toolCallId());
            }
            ObjectNode block = blocks.addObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", result.toolCallId());
            block.put("content", result.output());
            block.put("is_error", result.error());
        }
        if (blocks.isEmpty()) {
            throw invalid("Tool messages cannot be empty");
        }
        return blocks;
    }

    private ObjectNode imageBlock(ChatRequest.Image image, ImageBudget budget)
            throws AnthropicTranslationException {
        String mediaType = image.mediaType().toLowerCase(Locale.ROOT);
        if (!IMAGE_MEDIA_TYPES.contains(mediaType)) {
            throw invalid("Unsupported Anthropic image media type");
        }
        byte[] data = image.data();
        budget.add(data.length);
        ObjectNode block = Json.MAPPER.createObjectNode();
        block.put("type", "image");
        ObjectNode source = block.putObject("source");
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", Base64.getEncoder().encodeToString(data));
        return block;
    }

    private ObjectNode toolCallBlock(ChatRequest.ToolCall call)
            throws AnthropicTranslationException {
        JsonNode arguments;
        try {
            arguments = Json.MAPPER.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(call.argumentsJson());
        } catch (IOException error) {
            throw invalid("Tool-call arguments must be valid JSON");
        }
        if (arguments == null || !arguments.isObject()) {
            throw invalid("Tool-call arguments must be a JSON object");
        }
        ObjectNode block = Json.MAPPER.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", call.id());
        block.put("name", call.name());
        block.set("input", arguments);
        return block;
    }

    private static void addReasoningBlock(
            ArrayNode blocks, ChatRequest.Reasoning reasoning) {
        if (reasoning.redactedData() != null) {
            ObjectNode redacted = blocks.addObject();
            redacted.put("type", "redacted_thinking");
            redacted.set("data", reasoning.redactedData());
        }
        if (!reasoning.text().isEmpty() || !reasoning.signature().isEmpty()) {
            ObjectNode thinking = blocks.addObject();
            thinking.put("type", "thinking");
            thinking.put("thinking", reasoning.text());
            thinking.put("signature", reasoning.signature());
        }
    }

    private static void appendMessage(
            ArrayNode messages, String role, ArrayNode blocks) {
        if (!messages.isEmpty()) {
            ObjectNode previous = (ObjectNode) messages.get(messages.size() - 1);
            if (role.equals(previous.path("role").asText())) {
                ArrayNode previousContent = (ArrayNode) previous.path("content");
                blocks.forEach(previousContent::add);
                return;
            }
        }
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.set("content", blocks);
    }

    private static void addTools(ObjectNode root, ChatRequest request)
            throws AnthropicTranslationException {
        if (request.toolChoice() instanceof ChatRequest.ToolChoice.None) {
            return;
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            Set<String> names = new HashSet<>();
            for (ChatRequest.ToolDefinition definition : request.tools()) {
                if (!names.add(definition.name())) {
                    throw invalid("Duplicate tool definition: " + definition.name());
                }
                if (!definition.inputSchema().isObject()) {
                    throw invalid("Tool input schemas must be JSON objects");
                }
                ObjectNode tool = tools.addObject();
                tool.put("name", definition.name());
                tool.put("description", definition.description());
                tool.set("input_schema", definition.inputSchema());
            }
        }
        if (request.toolChoice() instanceof ChatRequest.ToolChoice.Auto) {
            if (!request.tools().isEmpty()) {
                root.putObject("tool_choice").put("type", "auto");
            }
        } else if (request.toolChoice() instanceof ChatRequest.ToolChoice.Required) {
            if (request.tools().isEmpty()) {
                throw invalid("Required tool choice needs at least one defined tool");
            }
            root.putObject("tool_choice").put("type", "any");
        } else if (request.toolChoice() instanceof ChatRequest.ToolChoice.Named named) {
            boolean exists = request.tools().stream()
                    .anyMatch(tool -> tool.name().equals(named.name()));
            if (!exists) {
                throw invalid("Named tool choice does not match a defined tool");
            }
            ObjectNode choice = root.putObject("tool_choice");
            choice.put("type", "tool");
            choice.put("name", named.name());
        }
    }

    private static void addReasoning(ObjectNode root, String effort)
            throws AnthropicTranslationException {
        if (effort == null) {
            return;
        }
        String normalized = effort.strip().toLowerCase(Locale.ROOT);
        if (!REASONING_EFFORTS.contains(normalized)) {
            throw invalid("Unsupported reasoning effort");
        }
        root.putObject("thinking").put("type", "adaptive");
        root.putObject("output_config").put("effort", normalized);
    }

    private static void requireNoOutstanding(Set<String> outstanding)
            throws AnthropicTranslationException {
        if (!outstanding.isEmpty()) {
            throw invalid("Every assistant tool call must be followed by its tool result");
        }
    }

    private static AnthropicTranslationException invalid(String message) {
        return new AnthropicTranslationException(message);
    }

    private static final class ImageBudget {
        private int count;
        private long bytes;

        private void add(int size) throws AnthropicTranslationException {
            count++;
            bytes += size;
            if (size > MAX_IMAGE_BYTES || count > MAX_IMAGE_COUNT
                    || bytes > MAX_TOTAL_IMAGE_BYTES) {
                throw invalid("Anthropic image limits exceeded");
            }
        }
    }
}
