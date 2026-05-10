package com.aiproxyoauth.server;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.Map;

public class HealthHandler implements Handler {

    @Override
    public void handle(Context ctx) {
        // replay_state signals that the proxy has no durable local conversation store.
        // Responses may use bounded same-process replay when cached references are available.
        JsonHelper.toJsonResponse(ctx, Map.of(
                "ok", true,
                "replay_state", "stateless"
        ));
    }
}
