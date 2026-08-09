package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.ProviderError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnthropicErrorParserTest {
    @Test
    void mapsKnownAnthropicErrorTypes() {
        ProviderError error = AnthropicErrorParser.parse(429, """
                {"type":"error","error":{"type":"rate_limit_error","message":"Slow down"}}
                """);

        assertEquals(ProviderError.Kind.RATE_LIMIT, error.kind());
        assertEquals(429, error.httpStatus());
        assertEquals("Slow down", error.message());
    }

    @Test
    void malformedErrorsAreGenericAndNeverEchoBody() {
        String secret = "secret-body-marker";
        ProviderError error = AnthropicErrorParser.parse(502, "malformed-" + secret);

        assertEquals(ProviderError.Kind.PROTOCOL, error.kind());
        assertFalse(error.message().contains(secret));
    }

    @Test
    void authenticationErrorsDoNotEchoPotentialCredentials() {
        String secret = "Bearer credential-that-must-not-leak";
        ProviderError error = AnthropicErrorParser.parse(401, """
                {"type":"error","error":{"type":"authentication_error","message":"%s"}}
                """.formatted(secret));

        assertEquals(ProviderError.Kind.AUTHENTICATION, error.kind());
        assertFalse(error.message().contains(secret));
    }
}
