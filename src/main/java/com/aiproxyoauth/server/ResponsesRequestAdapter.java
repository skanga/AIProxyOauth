package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class ResponsesRequestAdapter {
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    private static final int MAX_ENCODED_IMAGE_CHARACTERS = 7 * 1024 * 1024;

    public ChatRequest adapt(JsonNode root, String upstreamModel) {
        if (root == null || !root.isObject()) throw invalid("Request body must be a JSON object");
        List<ChatRequest.Message> messages = new ArrayList<>();
        JsonNode instructions = root.get("instructions");
        if (instructions != null && !instructions.isNull()) {
            if (!instructions.isTextual()) throw invalid("`instructions` must be a string");
            messages.add(new ChatRequest.Message(
                    ChatRequest.Role.SYSTEM, List.of(new ChatRequest.Text(instructions.asText()))));
        }
        adaptInput(root.get("input"), messages);
        if (messages.isEmpty()) throw invalid("`input` must contain at least one supported item");
        List<ChatRequest.ToolDefinition> tools = adaptTools(root.get("tools"));
        return new ChatRequest(
                upstreamModel,
                messages,
                tools,
                adaptToolChoice(root.get("tool_choice")),
                outputTokenLimit(root.get("max_output_tokens")),
                optionalDouble(root, "temperature"),
                optionalDouble(root, "top_p"),
                adaptStops(root.get("stop")),
                reasoningEffort(root.get("reasoning")),
                root.path("stream").asBoolean(false)
        );
    }

    private void adaptInput(JsonNode input, List<ChatRequest.Message> messages) {
        if (input == null || input.isNull()) throw invalid("`input` is required");
        if (input.isTextual()) {
            messages.add(message(ChatRequest.Role.USER, new ChatRequest.Text(input.asText())));
            return;
        }
        if (!input.isArray()) throw invalid("`input` must be a string or an array");
        for (JsonNode item : input) adaptItem(item, messages);
    }

    private void adaptItem(JsonNode item, List<ChatRequest.Message> messages) {
        if (item == null || !item.isObject()) throw invalid("Every input item must be an object");
        switch (item.path("type").asText()) {
            case "message" -> messages.add(adaptMessage(item));
            case "function_call" -> messages.add(message(
                    ChatRequest.Role.ASSISTANT,
                    new ChatRequest.ToolCall(
                            requiredText(item, "call_id"),
                            requiredText(item, "name"),
                            requiredText(item, "arguments"))));
            case "function_call_output" -> messages.add(message(
                    ChatRequest.Role.TOOL,
                    new ChatRequest.ToolResult(
                            requiredText(item, "call_id"), nodeText(item.get("output")),
                            item.path("is_error").asBoolean(false))));
            case "reasoning" -> messages.add(message(
                    ChatRequest.Role.ASSISTANT, adaptReasoning(item)));
            case "item_reference" -> throw invalid(
                    "Unresolved item_reference is not supported for Anthropic");
            default -> throw invalid("Unsupported Responses input item type: "
                    + item.path("type").asText("<missing>"));
        }
    }

    private ChatRequest.Message adaptMessage(JsonNode item) {
        ChatRequest.Role role = switch (item.path("role").asText()) {
            case "system" -> ChatRequest.Role.SYSTEM;
            case "developer" -> ChatRequest.Role.DEVELOPER;
            case "user" -> ChatRequest.Role.USER;
            case "assistant" -> ChatRequest.Role.ASSISTANT;
            default -> throw invalid("Unsupported Responses message role");
        };
        JsonNode content = item.get("content");
        List<ChatRequest.Content> parts = new ArrayList<>();
        if (content != null && content.isTextual()) {
            parts.add(new ChatRequest.Text(content.asText()));
        } else if (content != null && content.isArray()) {
            for (JsonNode part : content) parts.add(adaptContent(part));
        } else {
            throw invalid("Responses message content must be text or an array");
        }
        return new ChatRequest.Message(role, parts);
    }

    private ChatRequest.Content adaptContent(JsonNode part) {
        return switch (part.path("type").asText()) {
            case "input_text", "output_text" ->
                    new ChatRequest.Text(requiredText(part, "text"));
            case "input_image" -> adaptImage(part.path("image_url").asText());
            default -> throw invalid("Unsupported Responses message content type: "
                    + part.path("type").asText("<missing>"));
        };
    }

    private ChatRequest.Image adaptImage(String url) {
        if (url == null || !url.startsWith("data:image/")) {
            throw invalid("Claude image inputs must use an inline image data URL");
        }
        int semicolon = url.indexOf(';');
        int comma = url.indexOf(',');
        if (semicolon < 0 || comma < 0 || semicolon > comma
                || !url.substring(semicolon, comma).equals(";base64")) {
            throw invalid("Image data URL must be base64 encoded");
        }
        String encoded = url.substring(comma + 1);
        if (encoded.length() > MAX_ENCODED_IMAGE_CHARACTERS) {
            throw invalid("Image data URL exceeds the size limit");
        }
        try {
            return new ChatRequest.Image(
                    url.substring(5, semicolon), Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException error) {
            throw invalid("Image data URL contains invalid base64");
        }
    }

    private ChatRequest.Reasoning adaptReasoning(JsonNode item) {
        StringBuilder text = new StringBuilder();
        appendTextItems(text, item.get("summary"));
        appendTextItems(text, item.get("content"));
        String signature = item.path("reasoning_signature").asText("");
        JsonNode redacted = item.get("redacted_data");
        return new ChatRequest.Reasoning(text.toString(), signature, redacted);
    }

    private void appendTextItems(StringBuilder target, JsonNode items) {
        if (items == null || items.isNull()) return;
        if (!items.isArray()) throw invalid("Reasoning summary/content must be an array");
        for (JsonNode item : items) {
            String text = requiredText(item, "text");
            if (!target.isEmpty()) target.append('\n');
            target.append(text);
        }
    }

    private List<ChatRequest.ToolDefinition> adaptTools(JsonNode toolsNode) {
        if (toolsNode == null || toolsNode.isNull()) return List.of();
        if (!toolsNode.isArray()) throw invalid("`tools` must be an array");
        List<ChatRequest.ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            if (!"function".equals(tool.path("type").asText())) {
                throw invalid("Only function tools are supported for Anthropic");
            }
            JsonNode schema = tool.get("parameters");
            if (schema == null || schema.isNull()) {
                ObjectNode empty = Json.MAPPER.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", Json.MAPPER.createObjectNode());
                schema = empty;
            }
            tools.add(new ChatRequest.ToolDefinition(
                    requiredText(tool, "name"), tool.path("description").asText(""), schema));
        }
        return List.copyOf(tools);
    }

    private ChatRequest.ToolChoice adaptToolChoice(JsonNode choice) {
        if (choice == null || choice.isNull()) return new ChatRequest.ToolChoice.Auto();
        if (choice.isTextual()) {
            return switch (choice.asText()) {
                case "auto" -> new ChatRequest.ToolChoice.Auto();
                case "none" -> new ChatRequest.ToolChoice.None();
                case "required" -> new ChatRequest.ToolChoice.Required();
                default -> throw invalid("Unsupported `tool_choice`");
            };
        }
        if (choice.isObject() && "function".equals(choice.path("type").asText())) {
            return new ChatRequest.ToolChoice.Named(requiredText(choice, "name"));
        }
        throw invalid("Unsupported `tool_choice`");
    }

    private int outputTokenLimit(JsonNode value) {
        if (value == null || value.isNull()) return DEFAULT_MAX_OUTPUT_TOKENS;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() <= 0) {
            throw invalid("`max_output_tokens` must be a positive integer");
        }
        return value.asInt();
    }

    private Double optionalDouble(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid("`" + field + "` must be a number");
        return value.asDouble();
    }

    private List<String> adaptStops(JsonNode stop) {
        if (stop == null || stop.isNull()) return List.of();
        if (stop.isTextual()) return List.of(stop.asText());
        if (!stop.isArray()) throw invalid("`stop` must be a string or string array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : stop) {
            if (!value.isTextual()) throw invalid("Every stop sequence must be a string");
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private String reasoningEffort(JsonNode reasoning) {
        if (reasoning == null || reasoning.isNull()) return null;
        if (!reasoning.isObject()) throw invalid("`reasoning` must be an object");
        JsonNode effort = reasoning.get("effort");
        if (effort == null || effort.isNull()) return null;
        if (!effort.isTextual()) throw invalid("`reasoning.effort` must be a string");
        return effort.asText().toLowerCase(Locale.ROOT);
    }

    private ChatRequest.Message message(ChatRequest.Role role, ChatRequest.Content content) {
        return new ChatRequest.Message(role, List.of(content));
    }

    private String nodeText(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        return value.toString();
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("`" + field + "` must be a non-empty string");
        }
        return value.asText();
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
