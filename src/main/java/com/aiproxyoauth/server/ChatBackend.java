package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.ModelRoute;
import io.javalin.http.Context;

@FunctionalInterface
public interface ChatBackend {
    void handle(Context context, ModelRoute route) throws Exception;
    /** Failover requires known capabilities; older catalogs provide no image/reasoning metadata. */
    default boolean supports(tools.jackson.databind.JsonNode body, ModelRoute route, boolean responses) throws Exception {
        return !body.hasNonNull("reasoning") && !body.hasNonNull("reasoning_effort")
                && textAndToolsOnly(body.path(responses ? "input" : "messages"));
    }

    private static boolean textAndToolsOnly(tools.jackson.databind.JsonNode node) {
        String type = node.path("type").asString();
        if (java.util.Set.of("image_url", "input_image", "image", "input_audio", "audio", "file", "input_file", "reasoning").contains(type)) return false;
        for (var child : node) if (!textAndToolsOnly(child)) return false;
        return true;
    }
}
