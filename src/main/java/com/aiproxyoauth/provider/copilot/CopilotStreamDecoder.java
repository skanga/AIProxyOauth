package com.aiproxyoauth.provider.copilot;
import com.aiproxyoauth.provider.stream.*;
import java.util.List;
import java.util.*;
import com.aiproxyoauth.provider.ProviderError;
import com.aiproxyoauth.provider.anthropic.IncrementalSseFramer;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
public final class CopilotStreamDecoder implements CompletionStreamDecoder {
    private final boolean responses;
    private final IncrementalSseFramer framer = new IncrementalSseFramer(4 * 1024 * 1024);
    private final Map<String, Integer> blocks = new LinkedHashMap<>();
    private final Set<Integer> closed = new HashSet<>(), hasData = new HashSet<>();
    private boolean started, terminal;
    private FinishReason reason;
    public CopilotStreamDecoder(boolean responses) { this.responses = responses; }
    public List<CompletionEvent> feed(byte[] bytes) {
        if (terminal) return List.of();
        List<CompletionEvent> events = new ArrayList<>();
        try { framer.feed(bytes, frame -> consume(frame, events)); }
        catch (RuntimeException error) { fail(events); }
        return List.copyOf(events);
    }
    public List<CompletionEvent> end() {
        if (terminal) return List.of();
        List<CompletionEvent> events = new ArrayList<>(); fail(events); return events;
    }
    private void consume(IncrementalSseFramer.Event frame, List<CompletionEvent> out) {
        if (terminal || frame.data().isEmpty()) return;
        if (frame.data().equals("[DONE]")) {
            if (responses || !started || reason == null) { fail(out); return; }
            finish(out); return;
        }
        JsonNode node;
        try { node = Json.MAPPER.readTree(frame.data()); }
        catch (Exception error) { fail(out); return; }
        if (node == null || !node.isObject() || node.has("error")) { fail(out); return; }
        if (responses) response(node, out); else chat(node, out);
    }
    private void start(JsonNode node, List<CompletionEvent> out) {
        if (started) return;
        started = true;
        out.add(new CompletionEvent.Started(node.path("id").asText("copilot-" + UUID.randomUUID()),
                node.path("model").asText("copilot"), Math.max(0, node.path("created_at").asLong(node.path("created").asLong(System.currentTimeMillis() / 1000)))));
    }
    private int block(String key, BlockType type, String id, String name, List<CompletionEvent> out) {
        Integer existing = blocks.get(key);
        if (existing != null) return existing;
        int index = blocks.size(); blocks.put(key, index);
        out.add(new CompletionEvent.BlockStarted(index, type, id, name)); return index;
    }
    private void text(String key, BlockType type, String text, List<CompletionEvent> out) {
        if (text.isEmpty()) return;
        int index = block(key, type, null, null, out); hasData.add(index);
        if (type == BlockType.REASONING) out.add(new CompletionEvent.ReasoningDelta(index, text));
        else if (type == BlockType.REFUSAL) out.add(new CompletionEvent.RefusalDelta(index, text));
        else out.add(new CompletionEvent.TextDelta(index, text));
    }
    private void chat(JsonNode node, List<CompletionEvent> out) {
        if (!started && node.path("choices").isEmpty() && node.has("prompt_filter_results")) return;
        start(node, out);
        for (JsonNode choice : node.path("choices")) {
            if (choice.path("index").asInt() != 0) { fail(out); return; }
            JsonNode delta = choice.path("delta");
            text("text", BlockType.TEXT, delta.path("content").asText(""), out);
            text("reasoning", BlockType.REASONING, delta.path("reasoning_content").asText(""), out);
            text("refusal", BlockType.REFUSAL, delta.path("refusal").asText(""), out);
            for (JsonNode call : delta.path("tool_calls")) {
                String key = "tool/" + call.path("index").asInt();
                int index = block(key, BlockType.TOOL_CALL, call.path("id").asText(null), call.path("function").path("name").asText(null), out);
                String args = call.path("function").path("arguments").asText("");
                if (!args.isEmpty()) { hasData.add(index); out.add(new CompletionEvent.ToolCallArgumentsDelta(index, args)); }
            }
            if (choice.hasNonNull("finish_reason")) reason = switch (choice.path("finish_reason").asText()) {
                case "stop" -> FinishReason.STOP;
                case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
                case "length" -> FinishReason.LENGTH;
                case "content_filter" -> FinishReason.CONTENT_FILTER;
                default -> throw new IllegalArgumentException("Unknown finish reason");
            };
        }
        if (node.path("usage").isObject()) usage(node.path("usage"), out, false);
    }
    private void response(JsonNode node, List<CompletionEvent> out) {
        String type = node.path("type").asText();
        if (type.equals("response.created") || type.equals("response.in_progress")) { start(node.path("response"), out); return; }
        if (type.equals("response.failed") || type.equals("error")) { fail(out); return; }
        if (!started) { fail(out); return; }
        int itemIndex = node.path("output_index").asInt();
        String key = itemIndex + "/" + node.path("content_index").asInt();
        switch (type) {
            case "response.output_item.added" -> {
                JsonNode item = node.path("item");
                if (item.path("type").asText().equals("function_call")) block(itemIndex + "/tool", BlockType.TOOL_CALL,
                        item.path("call_id").asText(), item.path("name").asText(), out);
            }
            case "response.output_text.delta" -> text(key + "/text", BlockType.TEXT, node.path("delta").asText(), out);
            case "response.refusal.delta" -> text(key + "/refusal", BlockType.REFUSAL, node.path("delta").asText(), out);
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> text(key + "/reasoning", BlockType.REASONING, node.path("delta").asText(), out);
            case "response.function_call_arguments.delta" -> {
                Integer index = blocks.get(itemIndex + "/tool");
                if (index == null) { fail(out); return; }
                hasData.add(index); out.add(new CompletionEvent.ToolCallArgumentsDelta(index, node.path("delta").asText()));
            }
            case "response.function_call_arguments.done" -> {
                Integer index = blocks.get(itemIndex + "/tool");
                if (index != null && !hasData.contains(index)) {
                    hasData.add(index); out.add(new CompletionEvent.ToolCallArgumentsDelta(index, node.path("arguments").asText()));
                }
            }
            case "response.output_item.done" -> {
                snapshot(itemIndex, node.path("item"), out);
                blocks.forEach((k, index) -> {
                    if (k.startsWith(itemIndex + "/") && closed.add(index)) out.add(new CompletionEvent.BlockFinished(index));
                });
            }
            case "response.completed", "response.incomplete" -> {
                int index = 0;
                for (JsonNode item : node.path("response").path("output")) snapshot(index++, item, out);
                usage(node.path("response").path("usage"), out, true);
                reason = type.equals("response.incomplete") ? FinishReason.LENGTH
                        : blocks.keySet().stream().anyMatch(k -> k.endsWith("/tool")) ? FinishReason.TOOL_CALLS : FinishReason.STOP;
                finish(out);
            }
            default -> { }
        }
    }
    private void snapshot(int itemIndex, JsonNode item, List<CompletionEvent> out) {
        if (item.path("type").asText().equals("function_call")) {
            int index = block(itemIndex + "/tool", BlockType.TOOL_CALL, item.path("call_id").asText(), item.path("name").asText(), out);
            if (hasData.add(index)) out.add(new CompletionEvent.ToolCallArgumentsDelta(index, item.path("arguments").asText()));
        } else if (item.path("type").asText().equals("message")) {
            int partIndex = 0;
            for (JsonNode part : item.path("content")) {
                boolean refusal = part.path("type").asText().equals("refusal");
                String key = itemIndex + "/" + partIndex++ + (refusal ? "/refusal" : "/text");
                if (!blocks.containsKey(key)) text(key, refusal ? BlockType.REFUSAL : BlockType.TEXT,
                        part.path(refusal ? "refusal" : "text").asText(), out);
            }
        }
    }
    private void usage(JsonNode node, List<CompletionEvent> out, boolean responses) {
        if (!node.isObject()) return;
        out.add(new CompletionEvent.UsageSnapshot(node.path(responses ? "input_tokens" : "prompt_tokens").asLong(),
                node.path(responses ? "output_tokens" : "completion_tokens").asLong(), 0,
                node.path(responses ? "input_tokens_details" : "prompt_tokens_details").path("cached_tokens").asLong()));
    }
    private void finish(List<CompletionEvent> out) {
        for (int index : blocks.values()) if (closed.add(index)) out.add(new CompletionEvent.BlockFinished(index));
        terminal = true; out.add(new CompletionEvent.Finished(reason));
    }
    private void fail(List<CompletionEvent> out) {
        if (!terminal) out.add(new CompletionEvent.Error(ProviderError.of(ProviderError.Kind.PROTOCOL,
                "Copilot returned a malformed, failed, or incomplete stream")));
        terminal = true;
    }
}
