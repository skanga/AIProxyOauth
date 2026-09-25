package com.aiproxyoauth.server;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

import static com.aiproxyoauth.util.Json.MAPPER;

public final class ResponsesRequestSanitizer {

    private static final int MAX_CONVERTED_OUTPUT_CHARS = 16 * 1024;

    public ObjectNode sanitize(ObjectNode body, boolean defaultStore) {
        ObjectNode sanitized = body.deepCopy();
        sanitized.remove("max_output_tokens");

        boolean store = sanitized.has("store") ? sanitized.path("store").asBoolean(false) : defaultStore;
        if (store) {
            return sanitized;
        }

        JsonNode input = sanitized.get("input");
        if (input == null || !input.isArray()) {
            return sanitized;
        }

        Set<String> validCallIds = collectCallIds(input);
        ArrayNode sanitizedInput = MAPPER.createArrayNode();
        for (JsonNode item : input) {
            if (!item.isObject()) {
                sanitizedInput.add(item.deepCopy());
                continue;
            }

            String type = item.path("type").asString("");
            if ("item_reference".equals(type)) {
                continue;
            }

            ObjectNode copy = ((ObjectNode) item).deepCopy();
            copy.remove("id");
            if (isToolOutput(type) && !validCallIds.contains(copy.path("call_id").asString(""))) {
                sanitizedInput.add(toAssistantMessage(copy));
            } else {
                sanitizedInput.add(copy);
            }
        }
        sanitized.set("input", sanitizedInput);
        return sanitized;
    }

    private Set<String> collectCallIds(JsonNode input) {
        Set<String> callIds = new HashSet<>();
        for (JsonNode item : input) {
            if (!item.isObject()) {
                continue;
            }
            String type = item.path("type").asString("");
            if (isToolCall(type)) {
                String callId = item.path("call_id").asString("");
                if (!callId.isBlank()) {
                    callIds.add(callId);
                }
            }
        }
        return callIds;
    }

    private static boolean isToolCall(String type) {
        return "function_call".equals(type)
                || "custom_tool_call".equals(type)
                || "local_shell_call".equals(type);
    }

    private static boolean isToolOutput(String type) {
        return "function_call_output".equals(type)
                || "custom_tool_call_output".equals(type)
                || "local_shell_call_output".equals(type);
    }

    private ObjectNode toAssistantMessage(ObjectNode outputItem) {
        String callId = outputItem.path("call_id").asString("");
        String output = extractOutputText(outputItem);
        boolean truncated = output.length() > MAX_CONVERTED_OUTPUT_CHARS;
        if (truncated) {
            output = output.substring(0, MAX_CONVERTED_OUTPUT_CHARS);
        }

        ObjectNode message = MAPPER.createObjectNode();
        message.put("type", "message");
        message.put("role", "assistant");
        String suffix = truncated ? "\n[truncated]" : "";
        message.put("content", "[Previous read result; call_id=" + callId + "]: " + output + suffix);
        return message;
    }

    private String extractOutputText(ObjectNode outputItem) {
        JsonNode output = outputItem.get("output");
        if (output != null && !output.isMissingNode() && !output.isNull()) {
            return nodeToText(output);
        }

        JsonNode content = outputItem.get("content");
        if (content != null && !content.isMissingNode() && !content.isNull()) {
            return nodeToText(content);
        }

        StringBuilder combined = new StringBuilder();
        appendField(combined, outputItem, "stdout");
        appendField(combined, outputItem, "stderr");
        return combined.toString();
    }

    private void appendField(StringBuilder target, ObjectNode node, String fieldName) {
        String value = node.path(fieldName).asString("");
        if (value.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value);
    }

    private String nodeToText(JsonNode node) {
        if (node.isString()) {
            return node.asString();
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JacksonException e) {
            return node.asString("");
        }
    }
}
