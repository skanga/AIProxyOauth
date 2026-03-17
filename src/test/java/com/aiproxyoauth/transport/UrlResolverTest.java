package com.aiproxyoauth.transport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UrlResolverTest {
    @Test
    void resolveTargetUrl_appendsPath() {
        String base = "https://example.com/api";
        String resolved = UrlResolver.resolveTargetUrl("/v1/chat", base);
        assertEquals("https://example.com/api/chat", resolved);
    }

    @Test
    void resolveTargetUrl_handlesSlashes() {
        String base = "https://example.com/api/";
        String resolved = UrlResolver.resolveTargetUrl("/v1/chat", base);
        assertEquals("https://example.com/api/chat", resolved);
    }

    @Test
    void resolveTargetUrl_stripsV1Prefix() {
        String base = "https://example.com/api";
        String resolved = UrlResolver.resolveTargetUrl("/v1/chat", base);
        assertEquals("https://example.com/api/chat", resolved);
    }

    // --- HTTP input (absolute URL) ---

    @Test
    void resolveTargetUrl_absoluteHttpInput_usesBaseOriginAndBasePath() {
        // Full URL input: host is replaced with base origin, /v1 prefix stripped
        String base = "https://chatgpt.com/backend-api/codex";
        String input = "https://chatgpt.com/backend-api/codex/v1/responses";
        String resolved = UrlResolver.resolveTargetUrl(input, base);
        assertEquals("https://chatgpt.com/backend-api/codex/responses", resolved);
    }

    @Test
    void resolveTargetUrl_absoluteHttpInput_queryStringPreserved() {
        String base = "https://chatgpt.com/backend-api/codex";
        String input = "https://chatgpt.com/backend-api/codex/v1/models?version=2";
        String resolved = UrlResolver.resolveTargetUrl(input, base);
        assertEquals("https://chatgpt.com/backend-api/codex/models?version=2", resolved);
    }

    @Test
    void resolveTargetUrl_absoluteHttpInput_differentOrigin_originReplacedWithBase() {
        // Input from a different host: base origin wins, path is rewritten
        String base = "https://chatgpt.com/backend-api/codex";
        String input = "https://other.host.com/backend-api/codex/v1/responses";
        String resolved = UrlResolver.resolveTargetUrl(input, base);
        assertEquals("https://chatgpt.com/backend-api/codex/responses", resolved);
    }
}
