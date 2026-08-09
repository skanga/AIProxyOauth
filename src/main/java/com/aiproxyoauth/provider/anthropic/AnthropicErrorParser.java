package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.ProviderError;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

public final class AnthropicErrorParser {
    private static final int MAX_MESSAGE_CHARACTERS = 1024;

    private AnthropicErrorParser() {
    }

    public static ProviderError parse(int status, String body) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (Exception error) {
            return ProviderError.of(
                    ProviderError.Kind.PROTOCOL,
                    "Anthropic returned a malformed error response"
            );
        }
        JsonNode error = root == null ? null : root.path("error");
        if (error == null || !error.isObject()) {
            return new ProviderError(
                    kindForStatus(status),
                    normalizedStatus(status),
                    "Anthropic request failed"
            );
        }
        ProviderError.Kind kind = kindForType(error.path("type").asText(), status);
        String message = error.path("message").asText();
        if (kind == ProviderError.Kind.AUTHENTICATION) {
            message = "Anthropic authentication failed";
        } else if (message.isBlank()) {
            message = "Anthropic request failed";
        } else if (message.length() > MAX_MESSAGE_CHARACTERS) {
            message = message.substring(0, MAX_MESSAGE_CHARACTERS);
        }
        return new ProviderError(kind, normalizedStatus(status), message);
    }

    private static ProviderError.Kind kindForType(String type, int status) {
        return switch (type) {
            case "invalid_request_error" -> ProviderError.Kind.INVALID_REQUEST;
            case "authentication_error" -> ProviderError.Kind.AUTHENTICATION;
            case "permission_error" -> ProviderError.Kind.PERMISSION;
            case "not_found_error" -> ProviderError.Kind.NOT_FOUND;
            case "rate_limit_error" -> ProviderError.Kind.RATE_LIMIT;
            case "request_too_large" -> ProviderError.Kind.REQUEST_TOO_LARGE;
            case "overloaded_error" -> ProviderError.Kind.OVERLOADED;
            default -> kindForStatus(status);
        };
    }

    private static ProviderError.Kind kindForStatus(int status) {
        return switch (status) {
            case 400 -> ProviderError.Kind.INVALID_REQUEST;
            case 401 -> ProviderError.Kind.AUTHENTICATION;
            case 403 -> ProviderError.Kind.PERMISSION;
            case 404 -> ProviderError.Kind.NOT_FOUND;
            case 413 -> ProviderError.Kind.REQUEST_TOO_LARGE;
            case 429 -> ProviderError.Kind.RATE_LIMIT;
            case 529 -> ProviderError.Kind.OVERLOADED;
            default -> ProviderError.Kind.TRANSPORT;
        };
    }

    private static int normalizedStatus(int status) {
        return status >= 400 && status <= 599 ? status : 502;
    }
}
