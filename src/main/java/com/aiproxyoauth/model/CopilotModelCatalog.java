package com.aiproxyoauth.model;
import com.aiproxyoauth.provider.*;
import com.aiproxyoauth.provider.copilot.CopilotClient;
import java.time.Clock;
import java.util.List;
import java.util.*;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import com.aiproxyoauth.provider.chat.ChatRequest;
public final class CopilotModelCatalog implements ProviderModelCatalog {
    private static final List<String> ENDPOINTS = List.of("/chat/completions", "/responses", "/v1/messages");
    private final CopilotClient client;
    private final List<String> allowlist;
    private final Clock clock;
    private Map<String, JsonNode> metadata = Map.of();
    private List<ProviderModel> cached;
    private Instant fetched = Instant.MIN;
    private String identity;
    public CopilotModelCatalog(CopilotClient client, List<String> models, Clock clock) {
        this.client = client; this.allowlist = List.copyOf(models); this.clock = clock;
    }
    public ProviderId provider() { return ProviderId.COPILOT; }
    public synchronized List<ProviderModel> resolveModels() throws Exception {
        String current = client.identity();
        if (!current.equals(identity)) { cached = null; metadata = Map.of(); fetched = Instant.MIN; identity = current; }
        Instant now = clock.instant();
        if (cached != null && now.isBefore(fetched.plusSeconds(300))) return cached;
        try {
            JsonNode data = client.models().path("data");
            if (!data.isArray()) throw new java.io.IOException("Copilot model catalog has no data array");
            Map<String, JsonNode> entries = new LinkedHashMap<>();
            List<ProviderModel> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asString();
                if (id.isBlank() || !item.path("capabilities").path("type").asString("chat").equals("chat")) continue;
                if (!allowlist.isEmpty() && !allowlist.contains(id)) continue;
                if (endpoints(item).isEmpty()) continue;
                entries.put(id, item.deepCopy());
                JsonNode tools = item.path("capabilities").path("supports").path("tool_calls");
                models.add(new ProviderModel(id, item.path("name").asString(id), ProviderId.COPILOT, List.of(),
                        tools.isBoolean() ? Optional.of(tools.asBoolean()) : Optional.empty(),
                        Math.max(0, item.path("capabilities").path("limits").path("max_context_window_tokens").asInt())));
            }
            metadata = Collections.unmodifiableMap(entries); cached = List.copyOf(models); fetched = now;
            return cached;
        } catch (Exception error) {
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); throw error; }
            if (cached != null && now.isBefore(fetched.plusSeconds(3600))) return cached;
            throw error;
        }
    }
    private static List<String> endpoints(JsonNode item) {
        if (!item.has("supported_endpoints")) return List.of("/chat/completions"); // Verified legacy chat catalog.
        List<String> values = new ArrayList<>();
        item.path("supported_endpoints").forEach(e -> { if (ENDPOINTS.contains(e.asString())) values.add(e.asString()); });
        return List.copyOf(values);
    }
    public synchronized String endpoint(String id, boolean responses) throws Exception {
        resolveModels();
        JsonNode item = metadata.get(id);
        if (item == null) throw new IllegalArgumentException("Copilot model is not available: " + id);
        var endpoints = endpoints(item);
        String preferred = responses ? "/responses" : "/chat/completions";
        return endpoints.contains(preferred) ? preferred : ENDPOINTS.stream().filter(endpoints::contains).findFirst().orElseThrow();
    }
    public synchronized void validate(ChatRequest request) throws Exception {
        resolveModels();
        JsonNode model = metadata.get(request.model());
        if (model == null) throw new IllegalArgumentException("Copilot model is not available: " + request.model());
        JsonNode supports = model.path("capabilities").path("supports");
        if (supports.has("streaming") && !supports.path("streaming").asBoolean()) {
            throw new IllegalArgumentException("The selected Copilot model does not support the required streaming protocol");
        }
        if (!request.tools().isEmpty() && !supports.path("tool_calls").asBoolean(false)) {
            throw new IllegalArgumentException("tools are not supported by the selected Copilot model");
        }
        int images = 0;
        JsonNode vision = model.path("capabilities").path("limits").path("vision");
        for (var message : request.messages()) for (var part : message.content()) {
            if (part instanceof ChatRequest.Image image) {
                images++;
                if (!supports.path("vision").asBoolean(false)) throw new IllegalArgumentException("image input is not supported by the selected Copilot model");
                if (vision.path("max_prompt_image_size").asLong() > 0 && image.data().length > vision.path("max_prompt_image_size").asLong()) {
                    throw new IllegalArgumentException("Image exceeds the selected Copilot model's byte limit");
                }
                if (vision.path("supported_media_types").isArray()) {
                    boolean allowed = false;
                    for (JsonNode type : vision.path("supported_media_types")) if (type.asString().equals(image.mediaType())) allowed = true;
                    if (!allowed) throw new IllegalArgumentException("Image media type is not supported by the selected Copilot model");
                }
            }
        }
        if (vision.path("max_prompt_images").asInt() > 0 && images > vision.path("max_prompt_images").asInt()) {
            throw new IllegalArgumentException("Too many images for the selected Copilot model");
        }
        if (request.reasoningEffort() != null) {
            JsonNode efforts = supports.path("reasoning_effort");
            boolean found = false;
            for (JsonNode effort : efforts) if (effort.asString().equals(request.reasoningEffort())) found = true;
            if (!found) throw new IllegalArgumentException("reasoning effort is not advertised by the selected Copilot model");
        }
        int maximum = model.path("capabilities").path("limits").path("max_output_tokens").asInt();
        if (maximum > 0 && request.maxOutputTokens() > maximum) throw new IllegalArgumentException("max output tokens exceeds the model limit");
    }
}
