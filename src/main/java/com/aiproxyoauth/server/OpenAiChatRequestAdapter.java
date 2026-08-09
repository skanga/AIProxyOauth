package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class OpenAiChatRequestAdapter {
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    private static final int MAX_ENCODED_IMAGE_CHARACTERS = 7 * 1024 * 1024;

    public ChatRequest adapt(JsonNode root, String upstreamModel) {
        if (root == null || !root.isObject()) {
            throw invalid("Request body must be a JSON object");
        }
        JsonNode messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            throw invalid("`messages` must be an array");
        }
        List<ChatRequest.Message> messages = new ArrayList<>();
        for (JsonNode message : messagesNode) {
            messages.add(adaptMessage(message));
        }
        if (messages.isEmpty()) {
            throw invalid("`messages` cannot be empty");
        }
        List<ChatRequest.ToolDefinition> tools = adaptTools(root.path("tools"));
        return new ChatRequest(
                upstreamModel,
                messages,
                tools,
                adaptToolChoice(root.get("tool_choice")),
                outputTokenLimit(root),
                optionalDouble(root, "temperature"),
                optionalDouble(root, "top_p"),
                adaptStops(root.get("stop")),
                optionalText(root, "reasoning_effort"),
                root.path("stream").asBoolean(false)
        );
    }

    private ChatRequest.Message adaptMessage(JsonNode message) {
        if (message == null || !message.isObject()) throw invalid("Each message must be an object");
        ChatRequest.Role role = switch (message.path("role").asText()) {
            case "system" -> ChatRequest.Role.SYSTEM;
            case "developer" -> ChatRequest.Role.DEVELOPER;
            case "user" -> ChatRequest.Role.USER;
            case "assistant" -> ChatRequest.Role.ASSISTANT;
            case "tool" -> ChatRequest.Role.TOOL;
            default -> throw invalid("Unsupported message role");
        };
        List<ChatRequest.Content> content = new ArrayList<>();
        if (role == ChatRequest.Role.TOOL) {
            String callId = requiredText(message, "tool_call_id");
            content.add(new ChatRequest.ToolResult(callId, textContent(message.get("content")),
                    message.path("is_error").asBoolean(false)));
            return new ChatRequest.Message(role, content);
        }
        addContent(content, message.get("content"));
        if (role == ChatRequest.Role.ASSISTANT) {
            JsonNode calls = message.get("tool_calls");
            if (calls != null && !calls.isNull()) {
                if (!calls.isArray()) throw invalid("`tool_calls` must be an array");
                for (JsonNode call : calls) {
                    JsonNode function = call.path("function");
                    content.add(new ChatRequest.ToolCall(
                            requiredText(call, "id"),
                            requiredText(function, "name"),
                            requiredText(function, "arguments")
                    ));
                }
            }
        }
        return new ChatRequest.Message(role, content);
    }

    private void addContent(List<ChatRequest.Content> target, JsonNode content) {
        if (content == null || content.isNull()) return;
        if (content.isTextual()) {
            target.add(new ChatRequest.Text(content.asText()));
            return;
        }
        if (!content.isArray()) throw invalid("Message content must be text or an array");
        for (JsonNode part : content) {
            String type = part.path("type").asText();
            if ("text".equals(type)) {
                target.add(new ChatRequest.Text(requiredText(part, "text")));
            } else if ("image_url".equals(type)) {
                target.add(adaptImage(part.path("image_url").path("url").asText()));
            } else {
                throw invalid("Unsupported message content type: " + type);
            }
        }
    }

    private ChatRequest.Image adaptImage(String url) {
        if (url == null || !url.startsWith("data:image/")) {
            throw invalid("Claude image inputs must use an inline image data URL");
        }
        int separator = url.indexOf(',');
        if (separator < 0 || !url.substring(0, separator).endsWith(";base64")) {
            throw invalid("Image data URL must be base64 encoded");
        }
        String mediaType = url.substring(5, url.indexOf(';'));
        String encoded = url.substring(separator + 1);
        if (encoded.length() > MAX_ENCODED_IMAGE_CHARACTERS) {
            throw invalid("Image data URL exceeds the size limit");
        }
        try {
            return new ChatRequest.Image(mediaType, Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException error) {
            throw invalid("Image data URL contains invalid base64");
        }
    }

    private List<ChatRequest.ToolDefinition> adaptTools(JsonNode toolsNode) {
        if (toolsNode == null || toolsNode.isMissingNode() || toolsNode.isNull()) return List.of();
        if (!toolsNode.isArray()) throw invalid("`tools` must be an array");
        List<ChatRequest.ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            if (!"function".equals(tool.path("type").asText())) {
                throw invalid("Only function tools are supported");
            }
            JsonNode function = tool.path("function");
            JsonNode schema = function.get("parameters");
            if (schema == null || schema.isNull()) {
                ObjectNode empty = Json.MAPPER.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", Json.MAPPER.createObjectNode());
                schema = empty;
            }
            tools.add(new ChatRequest.ToolDefinition(
                    requiredText(function, "name"),
                    function.path("description").asText(""), schema));
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
            return new ChatRequest.ToolChoice.Named(requiredText(choice.path("function"), "name"));
        }
        throw invalid("Unsupported `tool_choice`");
    }

    private List<String> adaptStops(JsonNode stop) {
        if (stop == null || stop.isNull()) return List.of();
        if (stop.isTextual()) return List.of(stop.asText());
        if (!stop.isArray()) throw invalid("`stop` must be a string or an array of strings");
        List<String> stops = new ArrayList<>();
        stop.forEach(value -> {
            if (!value.isTextual()) throw invalid("Every stop sequence must be a string");
            stops.add(value.asText());
        });
        return List.copyOf(stops);
    }

    private int outputTokenLimit(JsonNode root) {
        JsonNode value = root.hasNonNull("max_completion_tokens")
                ? root.get("max_completion_tokens") : root.get("max_tokens");
        if (value == null || value.isNull()) return DEFAULT_MAX_OUTPUT_TOKENS;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() <= 0) {
            throw invalid("Output token limit must be a positive integer");
        }
        return value.asInt();
    }

    private Double optionalDouble(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid("`" + field + "` must be a number");
        return value.asDouble();
    }

    private String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("`" + field + "` must be a string");
        return value.asText().toLowerCase(Locale.ROOT);
    }

    private String textContent(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) throw invalid("Tool content must be text or an array");
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if (!"text".equals(part.path("type").asText())) {
                throw invalid("Tool content may contain only text");
            }
            text.append(requiredText(part, "text"));
        }
        return text.toString();
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
