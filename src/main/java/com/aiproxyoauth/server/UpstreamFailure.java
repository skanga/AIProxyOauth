package com.aiproxyoauth.server;
import java.io.IOException;
public final class UpstreamFailure extends IOException {
    private final int status;
    public UpstreamFailure(int status) { super("Upstream request failed with HTTP " + status); this.status = status; }
    public int status() { return status; }
    public boolean retryable() { return status == 429 || status >= 500; }
}
