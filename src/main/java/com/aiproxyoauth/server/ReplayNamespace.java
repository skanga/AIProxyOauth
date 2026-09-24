package com.aiproxyoauth.server;

import io.javalin.http.Context;

/**
 * Computes the per-client namespace used to isolate replay/state caches. Shared by every
 * responses backend and the routing dispatcher so an admin or keyed request always resolves
 * to the same namespace regardless of which cache is consulted.
 */
final class ReplayNamespace {
    private ReplayNamespace() {}

    static String of(Context context) {
        boolean admin = Boolean.TRUE.equals(context.attribute("isAdmin"));
        String keyFingerprint = context.attribute("keyFingerprint");
        String adminFingerprint = context.attribute("adminKeyFingerprint");
        String keyName = context.attribute("keyName");
        if (admin && adminFingerprint != null) return "admin-fp:" + adminFingerprint;
        if (keyFingerprint != null) return "key-fp:" + keyFingerprint;
        if (keyName != null) return "key:" + keyName;
        return admin ? "admin" : "open";
    }
}
