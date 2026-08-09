package com.aiproxyoauth.provider.anthropic.auth;

import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class AnthropicOAuthLogin implements OAuthLoginFlow {
    private static final char[] URL_SAFE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int VERIFIER_LENGTH = 128;
    private static final int STATE_LENGTH = 32;

    private final AnthropicCompatibilityProfile profile;
    private final AnthropicOAuthClient oauthClient;
    private final SecureRandom random;

    public AnthropicOAuthLogin(AnthropicCompatibilityProfile profile,
                               java.net.http.HttpClient httpClient) {
        this(profile, new AnthropicOAuthClient(profile, httpClient), new SecureRandom());
    }

    AnthropicOAuthLogin(AnthropicCompatibilityProfile profile,
                        AnthropicOAuthClient oauthClient,
                        SecureRandom random) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.oauthClient = Objects.requireNonNull(oauthClient, "oauthClient");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public Attempt newAttempt() {
        String verifier = randomString(VERIFIER_LENGTH);
        String state = randomString(STATE_LENGTH);
        return new Attempt(verifier, state, authorizationUri(profile, verifier, state));
    }

    @Override
    public OAuthTokenSet exchange(String callback, Attempt attempt) throws IOException {
        Objects.requireNonNull(attempt, "attempt");
        if (callback == null) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.INVALID_CALLBACK,
                    "OAuth callback is missing");
        }

        int separator = callback.indexOf('#');
        if (separator <= 0 || separator == callback.length() - 1
                || callback.indexOf('#', separator + 1) >= 0) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.INVALID_CALLBACK,
                    "OAuth callback must have the form code#state");
        }

        String code = callback.substring(0, separator);
        String returnedState = callback.substring(separator + 1);
        if (!MessageDigest.isEqual(
                attempt.state().getBytes(StandardCharsets.UTF_8),
                returnedState.getBytes(StandardCharsets.UTF_8))) {
            throw new AnthropicOAuthException(
                    AnthropicOAuthException.Kind.STATE_MISMATCH,
                    "OAuth state did not match");
        }

        String form = AnthropicOAuthClient.formField("grant_type", "authorization_code")
                + "&" + AnthropicOAuthClient.formField("code", code)
                + "&" + AnthropicOAuthClient.formField("client_id", profile.clientId())
                + "&" + AnthropicOAuthClient.formField("redirect_uri", profile.redirectUri().toString())
                + "&" + AnthropicOAuthClient.formField("code_verifier", attempt.verifier())
                + "&" + AnthropicOAuthClient.formField("state", attempt.state());
        return oauthClient.exchange(form);
    }

    public static URI authorizationUri(AnthropicCompatibilityProfile profile,
                                       String verifier,
                                       String state) {
        Objects.requireNonNull(profile, "profile");
        if (verifier == null || verifier.isBlank()) {
            throw new IllegalArgumentException("verifier must not be blank");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }

        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(verifier));
        String query = AnthropicOAuthClient.queryField("response_type", "code")
                + "&" + AnthropicOAuthClient.queryField("client_id", profile.clientId())
                + "&" + AnthropicOAuthClient.queryField(
                        "redirect_uri", profile.redirectUri().toString())
                + "&" + AnthropicOAuthClient.queryField("scope", String.join(" ", profile.scopes()))
                + "&" + AnthropicOAuthClient.queryField("state", state)
                + "&" + AnthropicOAuthClient.queryField("code_challenge", challenge)
                + "&" + AnthropicOAuthClient.queryField("code_challenge_method", "S256")
                + "&" + AnthropicOAuthClient.queryField("code", "true");
        return URI.create(profile.authorizationUri() + "?" + query);
    }

    private String randomString(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(URL_SAFE_ALPHABET[random.nextInt(URL_SAFE_ALPHABET.length)]);
        }
        return value.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public record Attempt(String verifier, String state, URI authorizationUri) {
        public Attempt {
            Objects.requireNonNull(verifier, "verifier");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(authorizationUri, "authorizationUri");
        }
    }
}
