package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.sse.SseCollector;
import com.aiproxyoauth.sse.SseParser;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public class ChatCompletionsHandler implements Handler {

    private final CodexHttpClient client;
    private final ServerConfig config;
    private final UsageTracker usageTracker;

    public ChatCompletionsHandler(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker) {
        this.client = client;
        this.config = config;
        this.usageTracker = usageTracker;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String bodyStr = ctx.body();
        JsonNode body = MAPPER.readTree(bodyStr);

        if (body == null || !body.isObject()) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.");
            return;
        }

        JsonNode messagesNode = body.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            JsonHelper.toErrorResponse(ctx, "`messages` must be an array.");
            return;
        }

        boolean wantsStream = body.path("stream").asBoolean(false);
        // When --models was specified, default to the first configured model.
        // "gpt-5.2" is the last-resort fallback for when no models were configured and
        // auto-discovery failed — in that case no better default is available without
        // an extra ModelResolver call. Callers can always override via the "model" field.
        String defaultModel = config.models() != null && !config.models().isEmpty()
                ? config.models().getFirst() : ServerConfig.DEFAULT_MODEL;
        String model = body.path("model").asText(defaultModel);

        // Build upstream Responses API request
        ObjectNode upstreamBody = buildUpstreamBody(body, model);

        // Always stream upstream
        HttpResponse<InputStream> upstream = client.request(
                "/responses", "POST",
                MAPPER.writeValueAsString(upstreamBody),
                Map.of("Content-Type", "application/json"));

        try (InputStream responseStream = upstream.body()) {
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                String rawBody = new String(responseStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                ctx.status(upstream.statusCode());
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE);
                ctx.result(JsonHelper.toUpstreamErrorBody(rawBody, upstream.statusCode()));
                return;
            }

            if (wantsStream) {
                streamToClient(ctx, responseStream, model);
            } else {
                nonStreamToClient(ctx, responseStream, model);
            }
        }
    }

    private ObjectNode buildUpstreamBody(JsonNode chatBody, String model) {
        ObjectNode upstream = MAPPER.createObjectNode();
        upstream.put("model", model);
        upstream.put("stream", true);
        upstream.put("store", config.store());

        // Convert messages to Responses API format
        ArrayNode input = MAPPER.createArrayNode();
        StringBuilder instructions = new StringBuilder();

        JsonNode messages = chatBody.get("messages");
        for (JsonNode msg : messages) {
            String role = msg.path("role").asText("");
            switch (role) {
                case "system", "developer" -> {
                    String text = extractTextContent(msg.get("content"));
                    if (!text.isEmpty()) {
                        if (!instructions.isEmpty()) instructions.append("\n");
                        instructions.append(text);
                    }
                }
                case "user" -> {
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("type", "message");
                    item.put("role", "user");
                    ArrayNode content = MAPPER.createArrayNode();
                    addContentParts(content, msg.get("content"));
                    item.set("content", content);
                    input.add(item);
                }
                case "assistant" -> {
                    String text = extractTextContent(msg.get("content"));
                    JsonNode toolCalls = msg.get("tool_calls");

                    if (!text.isEmpty()) {
                        ObjectNode item = MAPPER.createObjectNode();
                        item.put("type", "message");
                        item.put("role", "assistant");
                        ArrayNode content = MAPPER.createArrayNode();
                        ObjectNode textPart = MAPPER.createObjectNode();
                        textPart.put("type", "output_text");
                        textPart.put("text", text);
                        content.add(textPart);
                        item.set("content", content);
                        input.add(item);
                    }

                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            ObjectNode funcCall = MAPPER.createObjectNode();
                            funcCall.put("type", "function_call");
                            funcCall.put("call_id", tc.path("id").asText(""));
                            JsonNode func = tc.get("function");
                            if (func != null) {
                                funcCall.put("name", func.path("name").asText(""));
                                funcCall.put("arguments", func.path("arguments").asText("{}"));
                            }
                            input.add(funcCall);
                        }
                    }
                }
                case "tool" -> {
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("type", "function_call_output");
                    item.put("call_id", msg.path("tool_call_id").asText(""));
                    String content = extractTextContent(msg.get("content"));
                    item.put("output", content);
                    input.add(item);
                }
            }
        }

        upstream.set("input", input);

        // Set instructions
        String instr = instructions.toString();
        if (instr.isEmpty()) {
            instr = config.instructions();
        }
        upstream.put("instructions", instr);

        // Optional parameters
        if (chatBody.has("temperature") && !chatBody.get("temperature").isNull()) {
            upstream.set("temperature", chatBody.get("temperature"));
        }
        if (chatBody.has("top_p") && !chatBody.get("top_p").isNull()) {
            upstream.set("top_p", chatBody.get("top_p"));
        }
        // max_completion_tokens (newer SDK) takes precedence over deprecated max_tokens
        if (chatBody.has("max_completion_tokens") && !chatBody.get("max_completion_tokens").isNull()) {
            upstream.put("max_output_tokens", chatBody.get("max_completion_tokens").asInt());
        } else if (chatBody.has("max_tokens") && !chatBody.get("max_tokens").isNull()) {
            upstream.put("max_output_tokens", chatBody.get("max_tokens").asInt());
        }

        // Tools
        if (chatBody.has("tools") && chatBody.get("tools").isArray()) {
            ArrayNode tools = MAPPER.createArrayNode();
            for (JsonNode toolDef : chatBody.get("tools")) {
                if (!"function".equals(toolDef.path("type").asText())) continue;
                ObjectNode tool = MAPPER.createObjectNode();
                tool.put("type", "function");
                JsonNode func = toolDef.get("function");
                if (func != null) {
                    tool.put("name", func.path("name").asText(""));
                    if (func.has("description")) {
                        tool.put("description", func.path("description").asText(""));
                    }
                    if (func.has("parameters")) {
                        tool.set("parameters", func.get("parameters"));
                    } else {
                        ObjectNode defaultParams = MAPPER.createObjectNode();
                        defaultParams.put("type", "object");
                        defaultParams.set("properties", MAPPER.createObjectNode());
                        defaultParams.put("additionalProperties", true);
                        tool.set("parameters", defaultParams);
                    }
                }
                tools.add(tool);
            }
            upstream.set("tools", tools);
        }

        // Tool choice
        if (chatBody.has("tool_choice") && !chatBody.get("tool_choice").isNull()) {
            upstream.set("tool_choice", chatBody.get("tool_choice"));
        }

        // Reasoning effort
        if (chatBody.has("reasoning_effort") && !chatBody.get("reasoning_effort").isNull()) {
            ObjectNode reasoning = MAPPER.createObjectNode();
            reasoning.put("effort", chatBody.get("reasoning_effort").asText());
            upstream.set("reasoning", reasoning);
        }

        return upstream;
    }

    private void nonStreamToClient(Context ctx, InputStream upstreamBody, String model) throws Exception {
        JsonNode completedResponse = SseCollector.collectCompletedResponse(upstreamBody);

        String id = "chatcmpl_" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;

        ObjectNode result = MAPPER.createObjectNode();
        result.put("id", id);
        result.put("object", "chat.completion");
        result.put("created", created);
        result.put("model", model);

        ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);

        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");

        StringBuilder textContent = new StringBuilder();
        ArrayNode toolCalls = MAPPER.createArrayNode();
        String finishReason = "stop";

        JsonNode output = completedResponse.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                String type = item.path("type").asText("");
                switch (type) {
                    case "message" -> {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode part : content) {
                                if ("output_text".equals(part.path("type").asText())) {
                                    textContent.append(part.path("text").asText(""));
                                }
                            }
                        }
                    }
                    case "function_call" -> {
                        ObjectNode tc = MAPPER.createObjectNode();
                        tc.put("id", item.path("call_id").asText(""));
                        tc.put("type", "function");
                        ObjectNode func = MAPPER.createObjectNode();
                        func.put("name", item.path("name").asText(""));
                        func.put("arguments", item.path("arguments").asText("{}"));
                        tc.set("function", func);
                        toolCalls.add(tc);
                    }
                }
            }
        }

        if (!textContent.isEmpty()) {
            message.put("content", textContent.toString());
        } else {
            message.putNull("content");
        }
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }

        String status = completedResponse.path("status").asText("");
        finishReason = switch (status) {
            case "completed" -> toolCalls.isEmpty() ? "stop" : "tool_calls";
            case "incomplete" -> "length";
            case "failed", "cancelled" -> "stop";
            default -> toolCalls.isEmpty() ? "stop" : "tool_calls";
        };

        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        choices.add(choice);
        result.set("choices", choices);

        JsonNode usageNode = completedResponse.get("usage");
        usageTracker.record(ctx.attribute("keyName"),
                usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
        result.set("usage", JsonHelper.toUsage(usageNode));

        JsonHelper.toJsonResponse(ctx, result);
    }

    private void streamToClient(Context ctx, InputStream upstreamBody, String model) throws Exception {
        JsonHelper.setSseHeaders(ctx);
        OutputStream os = ctx.res().getOutputStream();

        String id = "chatcmpl_" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;
        Map<String, Integer> toolIndexes = new LinkedHashMap<>();
        boolean[] doneSent = {false};
        boolean[] finishSent = {false};

        // Send initial role chunk
        writeSseChunk(os, createChunk(id, created, model, createRoleDelta("assistant"), null));

        try {
            SseParser.iterateEvents(upstreamBody, event -> {
                try {
                    if (event.data() == null || event.data().isEmpty()) return;
                    if ("[DONE]".equals(event.data())) {
                        // If upstream sends [DONE] without a response.completed event (e.g. on
                        // error mid-stream), emit a synthetic finish chunk so clients don't hang
                        // waiting for a non-null finish_reason.
                        if (!finishSent[0]) {
                            writeSseChunk(os, createChunk(id, created, model, createEmptyDelta(), "stop"));
                            finishSent[0] = true;
                        }
                        os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        doneSent[0] = true;
                        return;
                    }

                    JsonNode parsed = MAPPER.readTree(event.data());
                    if (parsed == null || !parsed.isObject()) return;

                    String eventType = parsed.path("type").asText(event.event() != null ? event.event() : "");

                    switch (eventType) {
                        case "response.output_text.delta" -> {
                            String delta = parsed.path("delta").asText("");
                            if (!delta.isEmpty()) {
                                writeSseChunk(os, createChunk(id, created, model,
                                        createContentDelta(delta), null));
                            }
                        }
                        case "response.output_item.added" -> {
                            JsonNode item = parsed.get("item");
                            if (item != null && "function_call".equals(item.path("type").asText())) {
                                String callId = item.path("call_id").asText("");
                                String name = item.path("name").asText("");
                                int nextIndex = toolIndexes.size();
                                toolIndexes.put(callId, nextIndex);

                                ArrayNode tcArray = MAPPER.createArrayNode();
                                ObjectNode tc = MAPPER.createObjectNode();
                                tc.put("index", nextIndex);
                                tc.put("id", callId);
                                tc.put("type", "function");
                                ObjectNode func = MAPPER.createObjectNode();
                                func.put("name", name);
                                func.put("arguments", "");
                                tc.set("function", func);
                                tcArray.add(tc);

                                writeSseChunk(os, createChunk(id, created, model,
                                        createToolCallsDelta(tcArray), null));
                            }
                        }
                        case "response.function_call_arguments.delta" -> {
                            String callId = parsed.path("call_id").asText(
                                    parsed.path("item_id").asText(""));
                            String argDelta = parsed.path("delta").asText("");
                            Integer index = toolIndexes.get(callId);
                            if (index != null && !argDelta.isEmpty()) {
                                ArrayNode tcArray = MAPPER.createArrayNode();
                                ObjectNode tc = MAPPER.createObjectNode();
                                tc.put("index", index);
                                ObjectNode func = MAPPER.createObjectNode();
                                func.put("arguments", argDelta);
                                tc.set("function", func);
                                tcArray.add(tc);

                                writeSseChunk(os, createChunk(id, created, model,
                                        createToolCallsDelta(tcArray), null));
                            }
                        }
                        case "response.completed" -> {
                            JsonNode response = parsed.get("response");
                            String status = response != null ? response.path("status").asText("") : "";
                            String fr = switch (status) {
                                case "completed" -> toolIndexes.isEmpty() ? "stop" : "tool_calls";
                                case "incomplete" -> "length";
                                default -> "stop";
                            };

                            // Finish chunk
                            writeSseChunk(os, createChunk(id, created, model, createEmptyDelta(), fr));
                            finishSent[0] = true;

                            // Usage chunk
                            JsonNode usageNode = response != null ? response.get("usage") : null;
                            usageTracker.record(ctx.attribute("keyName"),
                                    usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                                    usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
                            ObjectNode usageChunk = MAPPER.createObjectNode();
                            usageChunk.put("id", id);
                            usageChunk.put("object", "chat.completion.chunk");
                            usageChunk.put("created", created);
                            usageChunk.put("model", model);
                            usageChunk.set("choices", MAPPER.createArrayNode());
                            usageChunk.set("usage", JsonHelper.toUsage(usageNode));
                            writeSseChunk(os, usageChunk);
                        }
                        case "response.failed", "response.cancelled" -> {
                            JsonNode response = parsed.get("response");
                            String errorMsg = response != null
                                    ? response.path("error").path("message").asText("Upstream response failed.")
                                    : "Upstream response failed.";
                            // Emit a finish chunk with "stop" so the client stream terminates cleanly,
                            // then write an error SSE event with details.
                            if (!finishSent[0]) {
                                writeSseChunk(os, createChunk(id, created, model, createEmptyDelta(), "stop"));
                                finishSent[0] = true;
                            }
                            ObjectNode errPayload = MAPPER.createObjectNode();
                            ObjectNode errObj = MAPPER.createObjectNode();
                            errObj.put("message", errorMsg);
                            errObj.put("type", "upstream_error");
                            errPayload.set("error", errObj);
                            String errLine = "event: error\ndata: " + MAPPER.writeValueAsString(errPayload) + "\n\n";
                            os.write(errLine.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                } catch (Exception e) {
                    System.err.println("Error processing SSE event: " + e);
                    throw new RuntimeException(e);
                }
            });
        } finally {
            // Guarantee a finish chunk + [DONE] are sent even if the upstream stream
            // ends abnormally (no [DONE] event and no response.completed).
            if (!doneSent[0]) {
                try {
                    if (!finishSent[0]) {
                        writeSseChunk(os, createChunk(id, created, model, createEmptyDelta(), "stop"));
                    }
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
            os.flush();
        }
    }

    private ObjectNode createChunk(String id, long created, String model,
                                    ObjectNode delta, String finishReason) {
        ObjectNode chunk = MAPPER.createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);

        ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        choices.add(choice);
        chunk.set("choices", choices);

        return chunk;
    }

    private ObjectNode createRoleDelta(String role) {
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("role", role);
        return delta;
    }

    private ObjectNode createContentDelta(String content) {
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("content", content);
        return delta;
    }

    private ObjectNode createToolCallsDelta(ArrayNode toolCalls) {
        ObjectNode delta = MAPPER.createObjectNode();
        delta.set("tool_calls", toolCalls);
        return delta;
    }

    private ObjectNode createEmptyDelta() {
        return MAPPER.createObjectNode();
    }

    private void writeSseChunk(OutputStream os, JsonNode data) throws Exception {
        String line = "data: " + MAPPER.writeValueAsString(data) + "\n\n";
        os.write(line.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private String extractTextContent(JsonNode content) {
        if (content == null) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isObject() && "text".equals(part.path("type").asText())) {
                    String text = part.path("text").asText("");
                    if (!text.isEmpty()) {
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private void addContentParts(ArrayNode target, JsonNode content) {
        if (content == null) return;
        if (content.isTextual()) {
            ObjectNode part = MAPPER.createObjectNode();
            part.put("type", "input_text");
            part.put("text", content.asText());
            target.add(part);
        } else if (content.isArray()) {
            for (JsonNode item : content) {
                if (item.isObject()) {
                    String type = item.path("type").asText("");
                    if ("text".equals(type)) {
                        ObjectNode part = MAPPER.createObjectNode();
                        part.put("type", "input_text");
                        part.put("text", item.path("text").asText(""));
                        target.add(part);
                    } else if ("image_url".equals(type)) {
                        ObjectNode part = MAPPER.createObjectNode();
                        part.put("type", "input_image");
                        JsonNode imageUrl = item.get("image_url");
                        if (imageUrl != null && imageUrl.has("url")) {
                            part.put("url", imageUrl.path("url").asText(""));
                        }
                        // Note: the OpenAI "detail" field ("low"/"high"/"auto") is intentionally
                        // not forwarded — the upstream Responses API does not expose an equivalent
                        // image resolution parameter.
                        target.add(part);
                    }
                }
            }
        }
    }
}
