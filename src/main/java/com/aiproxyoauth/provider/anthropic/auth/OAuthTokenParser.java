package com.aiproxyoauth.provider.anthropic.auth;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;


final class OAuthTokenParser {

    private OAuthTokenParser() {}

    static OAuthTokenSet parse(BoundedOAuthResponseReader.Response response)
            throws AnthropicOAuthException {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(response.body());
        } catch (Exception error) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.BAD_RESPONSE,
                    "OAuth endpoint returned invalid JSON."
            );
        }
        if (root == null || !root.isObject()) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.BAD_RESPONSE,
                    "OAuth endpoint returned an invalid response."
            );
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorCode = safeErrorCode(root.path("error").asText());
            String suffix = errorCode.isEmpty() ? "" : " (" + errorCode + ")";
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.API_ERROR,
                    "OAuth endpoint returned HTTP " + response.statusCode() + suffix + "."
            );
        }
        String accessToken = root.path("access_token").asText();
        if (accessToken.isBlank()) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.MISSING_TOKEN,
                    "OAuth endpoint returned no access token."
            );
        }
        long expiresIn = root.path("expires_in").asLong(0);
        if (expiresIn <= 0) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.BAD_RESPONSE,
                    "OAuth endpoint returned an invalid expiration."
            );
        }
        return new OAuthTokenSet(
                accessToken,
                root.path("refresh_token").asText(),
                expiresIn
        );
    }

    private static String safeErrorCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}")) {
            return "";
        }
        return value;
    }
}
