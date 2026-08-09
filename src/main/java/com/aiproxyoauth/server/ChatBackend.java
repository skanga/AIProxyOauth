package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.ModelRoute;
import io.javalin.http.Context;

@FunctionalInterface
public interface ChatBackend {
    void handle(Context context, ModelRoute route) throws Exception;
}
