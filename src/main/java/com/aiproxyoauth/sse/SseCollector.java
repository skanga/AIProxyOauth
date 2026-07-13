package com.aiproxyoauth.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SseCollector {

    private SseCollector() {}

    public static JsonNode collectCompletedResponse(InputStream input) throws IOException {
        JsonNode latestResponse = null;
        JsonNode latestError = null;
        StringBuilder outputTextDeltas = new StringBuilder();
        Map<String, ObjectNode> streamedFunctionCalls = new LinkedHashMap<>();
        Map<String, StringBuilder> streamedArguments = new LinkedHashMap<>();
        Map<String, String> callIdsByItemId = new LinkedHashMap<>();

        for (ServerSentEvent event : SseParser.parse(input)) {
            if (event.data() == null || event.data().isEmpty()) {
                continue;
            }

            try {
                JsonNode parsed = Json.MAPPER.readTree(event.data());
                if (parsed == null || !parsed.isObject()) {
                    continue;
                }

                if ("error".equals(event.event())) {
                    latestError = parsed;
                    continue;
                }

                // Only accept the response object from a response.completed event to avoid
                // mistaking partial response objects in other event types for the final result.
                String eventType = parsed.path("type").asText(event.event() != null ? event.event() : "");
                if ("response.output_text.delta".equals(eventType)) {
                    String delta = parsed.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        outputTextDeltas.append(delta);
                    }
                    continue;
                }

                if ("response.output_item.added".equals(eventType)
                        || "response.output_item.done".equals(eventType)) {
                    JsonNode item = parsed.get("item");
                    if (item != null && "function_call".equals(item.path("type").asText())) {
                        rememberFunctionCall(streamedFunctionCalls, streamedArguments,
                                callIdsByItemId, item);
                    }
                    continue;
                }

                if ("response.function_call_arguments.delta".equals(eventType)) {
                    String callId = eventCallId(parsed, callIdsByItemId);
                    if (!callId.isBlank()) {
                        streamedArguments.computeIfAbsent(callId, ignored -> new StringBuilder())
                                .append(parsed.path("delta").asText(""));
                    }
                    continue;
                }

                if ("response.function_call_arguments.done".equals(eventType)) {
                    String callId = eventCallId(parsed, callIdsByItemId);
                    if (!callId.isBlank()) {
                        String arguments = parsed.path("arguments").asText("");
                        streamedArguments.put(callId, new StringBuilder(arguments));
                    }
                    continue;
                }

                if ("response.completed".equals(eventType)) {
                    JsonNode response = parsed.get("response");
                    if (response != null && response.isObject()) {
                        latestResponse = response;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (latestResponse != null) {
            JsonNode result = latestResponse;
            if (!outputTextDeltas.isEmpty() && !containsOutputText(latestResponse)) {
                result = appendOutputText(result, outputTextDeltas.toString());
            }
            if (!streamedFunctionCalls.isEmpty()) {
                result = appendMissingFunctionCalls(result, streamedFunctionCalls, streamedArguments);
            }
            return result;
        }

        String errorInfo = latestError != null ? " Last error: " + latestError : "";
        throw new IOException("No completed response found in SSE stream." + errorInfo);
    }

    private static String eventCallId(JsonNode event, Map<String, String> callIdsByItemId) {
        String callId = event.path("call_id").asText("");
        if (!callId.isBlank()) {
            return callId;
        }
        return callIdsByItemId.getOrDefault(event.path("item_id").asText(""), "");
    }

    private static void rememberFunctionCall(Map<String, ObjectNode> calls,
                                             Map<String, StringBuilder> arguments,
                                             Map<String, String> callIdsByItemId,
                                             JsonNode item) {
        String callId = item.path("call_id").asText("");
        if (callId.isBlank()) return;
        String itemId = item.path("id").asText("");
        if (!itemId.isBlank()) callIdsByItemId.put(itemId, callId);
        ObjectNode call = calls.computeIfAbsent(callId, ignored -> {
            ObjectNode created = Json.MAPPER.createObjectNode();
            created.put("type", "function_call");
            created.put("call_id", callId);
            return created;
        });
        if (item.hasNonNull("name")) call.put("name", item.path("name").asText(""));
        String itemArguments = item.path("arguments").asText("");
        if (!itemArguments.isEmpty()) arguments.put(callId, new StringBuilder(itemArguments));
        else arguments.computeIfAbsent(callId, ignored -> new StringBuilder());
    }

    private static JsonNode appendMissingFunctionCalls(JsonNode response,
                                                       Map<String, ObjectNode> calls,
                                                       Map<String, StringBuilder> arguments) {
        ObjectNode copy = response.deepCopy();
        ArrayNode output = copy.withArray("output");
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText())) {
                existing.add(item.path("call_id").asText(""));
            }
        }
        for (Map.Entry<String, ObjectNode> entry : calls.entrySet()) {
            if (existing.contains(entry.getKey())) continue;
            ObjectNode call = entry.getValue().deepCopy();
            call.put("arguments", arguments.getOrDefault(entry.getKey(), new StringBuilder()).toString());
            output.add(call);
        }
        return copy;
    }

    private static boolean containsOutputText(JsonNode response) {
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return false;
        }
        for (JsonNode item : output) {
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if ("output_text".equals(part.path("type").asText()) && part.hasNonNull("text")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode appendOutputText(JsonNode response, String text) {
        ObjectNode copy = response.deepCopy();
        ArrayNode output;
        JsonNode existingOutput = copy.get("output");
        if (existingOutput != null && existingOutput.isArray()) {
            output = (ArrayNode) existingOutput;
        } else {
            output = Json.MAPPER.createArrayNode();
            copy.set("output", output);
        }

        ObjectNode message = Json.MAPPER.createObjectNode();
        message.put("type", "message");
        message.put("role", "assistant");

        ArrayNode content = Json.MAPPER.createArrayNode();
        ObjectNode textPart = Json.MAPPER.createObjectNode();
        textPart.put("type", "output_text");
        textPart.put("text", text);
        content.add(textPart);

        message.set("content", content);
        output.add(message);
        return copy;
    }
}
