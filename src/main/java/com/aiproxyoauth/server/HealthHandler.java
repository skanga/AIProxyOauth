package com.aiproxyoauth.server;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.Map;

public class HealthHandler implements Handler {

    @Override
    public void handle(Context ctx) {
        // replay_state signals to callers that this proxy operates in stateless mode:
        // it does not store conversation history, so clients must replay the full
        // conversation on each request.
        JsonHelper.toJsonResponse(ctx, Map.of(
                "ok", true,
                "replay_state", "stateless"
        ));
    }
}
