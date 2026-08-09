package com.aiproxyoauth.provider.anthropic.auth;

import com.aiproxyoauth.transport.BoundedBodyReader;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class BoundedOAuthResponseReader {

    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    record Response(int statusCode, byte[] body) {}

    private BoundedOAuthResponseReader() {}

    static Response send(HttpClient client, HttpRequest request)
            throws AnthropicOAuthException {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.NETWORK,
                    "OAuth request was interrupted.",
                    error
            );
        } catch (IOException | RuntimeException error) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.NETWORK,
                    "OAuth request failed.",
                    error
            );
        }

        try {
            byte[] bytes = BoundedBodyReader.read(response, MAX_RESPONSE_BYTES);
            return new Response(response.statusCode(), bytes);
        } catch (BoundedBodyReader.BodyTooLargeException error) {
            throw tooLarge();
        } catch (IOException error) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.NETWORK,
                    "OAuth response could not be read.",
                    error
            );
        }
    }

    private static AnthropicOAuthException tooLarge() {
        return new AnthropicOAuthException(
                AnthropicOAuthException.Kind.RESPONSE_TOO_LARGE,
                "OAuth response exceeded the 1048576-byte limit."
        );
    }
}
