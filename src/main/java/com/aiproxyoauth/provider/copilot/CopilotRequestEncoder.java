package com.aiproxyoauth.provider.copilot;
import com.aiproxyoauth.provider.chat.ChatRequest;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import com.aiproxyoauth.util.Json;
import com.aiproxyoauth.provider.anthropic.AnthropicRequestTranslator;
import java.util.Base64;
import java.util.Locale;
public final class CopilotRequestEncoder {
    public static ObjectNode encode(ChatRequest request, String endpoint) throws Exception {
        var names = new java.util.HashSet<String>();
        for (var tool : request.tools()) {
            if (!names.add(tool.name())) throw new IllegalArgumentException("Duplicate tool name");
        }
        if (request.toolChoice() instanceof ChatRequest.ToolChoice.Named named && !names.contains(named.name())) {
            throw new IllegalArgumentException("Named tool_choice must identify a declared function");
        }
        if (request.toolChoice() instanceof ChatRequest.ToolChoice.Required && names.isEmpty()) {
            throw new IllegalArgumentException("Required tool_choice needs at least one function");
        }
        if (endpoint.equals("/v1/messages")) {
            ObjectNode root = new AnthropicRequestTranslator().translate(request);
            root.put("stream", true);
            if (root.path("system").isEmpty()) root.remove("system");
            return root;
        }
        boolean responses = endpoint.equals("/responses");
        ObjectNode root = Json.MAPPER.createObjectNode().put("model", request.model()).put("stream", true);
        root.put(responses ? "max_output_tokens" : "max_completion_tokens", request.maxOutputTokens());
        if (responses) root.put("store", false);
        else root.putObject("stream_options").put("include_usage", true);
        if (request.temperature() != null) root.put("temperature", request.temperature());
        if (request.topP() != null) root.put("top_p", request.topP());
        if (!request.stopSequences().isEmpty()) {
            if (responses) throw new IllegalArgumentException("stop is unsupported by the Copilot Responses protocol");
            request.stopSequences().forEach(root.putArray("stop")::add);
        }
        if (request.reasoningEffort() != null) {
            if (responses) root.putObject("reasoning").put("effort", request.reasoningEffort());
            else root.put("reasoning_effort", request.reasoningEffort());
        }
        ArrayNode messages = root.putArray(responses ? "input" : "messages");
        for (var message : request.messages()) {
            String role = message.role().name().toLowerCase(Locale.ROOT);
            ObjectNode encoded = Json.MAPPER.createObjectNode().put("role", role);
            if (responses) encoded.put("type", "message");
            ArrayNode content = encoded.putArray("content");
            for (var part : message.content()) {
                if (responses && !(part instanceof ChatRequest.Text) && !(part instanceof ChatRequest.Image) && !content.isEmpty()) {
                    messages.add(encoded.deepCopy());
                    content.removeAll();
                }
                if (part instanceof ChatRequest.Text text) {
                    content.addObject().put("type", responses ? (role.equals("assistant") ? "output_text" : "input_text") : "text")
                            .put("text", text.text());
                } else if (part instanceof ChatRequest.Image image) {
                    String data = "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.data());
                    if (responses) content.addObject().put("type", "input_image").put("image_url", data);
                    else content.addObject().put("type", "image_url").putObject("image_url").put("url", data);
                } else if (part instanceof ChatRequest.ToolCall call) {
                    if (responses) messages.addObject().put("type", "function_call").put("call_id", call.id())
                            .put("name", call.name()).put("arguments", call.argumentsJson());
                    else {
                        ArrayNode calls = encoded.has("tool_calls") ? (ArrayNode) encoded.get("tool_calls") : encoded.putArray("tool_calls");
                        calls.addObject().put("id", call.id()).put("type", "function").putObject("function")
                                .put("name", call.name()).put("arguments", call.argumentsJson());
                    }
                } else if (part instanceof ChatRequest.ToolResult result) {
                    if (responses) messages.addObject().put("type", "function_call_output").put("call_id", result.toolCallId()).put("output", result.output());
                    else messages.addObject().put("role", "tool").put("tool_call_id", result.toolCallId()).put("content", result.output());
                } else if (part instanceof ChatRequest.Reasoning reasoning) {
                    if (!reasoning.signature().isEmpty() || reasoning.redactedData() != null) {
                        throw new IllegalArgumentException("Provider-specific reasoning state cannot be translated to this Copilot protocol");
                    }
                    if (responses) messages.addObject().put("type", "reasoning").putArray("summary")
                            .addObject().put("type", "summary_text").put("text", reasoning.text());
                    else encoded.put("reasoning_content", reasoning.text());
                }
            }
            if (!content.isEmpty() || encoded.has("tool_calls") || encoded.has("reasoning_content")) messages.add(encoded);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (var tool : request.tools()) {
                ObjectNode definition = tools.addObject().put("type", "function");
                if (!responses) definition = definition.putObject("function");
                definition.put("name", tool.name()).put("description", tool.description()).set("parameters", tool.inputSchema());
            }
            if (request.toolChoice() instanceof ChatRequest.ToolChoice.Named named) {
                ObjectNode choice = root.putObject("tool_choice").put("type", "function");
                if (responses) choice.put("name", named.name()); else choice.putObject("function").put("name", named.name());
            } else root.put("tool_choice", request.toolChoice() instanceof ChatRequest.ToolChoice.Required ? "required"
                    : request.toolChoice() instanceof ChatRequest.ToolChoice.None ? "none" : "auto");
        }
        return root;
    }
}
