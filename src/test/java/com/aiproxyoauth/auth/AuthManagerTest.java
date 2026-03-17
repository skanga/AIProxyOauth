package com.aiproxyoauth.auth;

import com.aiproxyoauth.config.ServerConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthManagerTest {

    private static ServerConfig minimalConfig() {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.111.0",
                ServerConfig.DEFAULT_BASE_URL,
                null, null, null,
                "", false,
                Map.of(), null
        );
    }

    /** Config that points oauthFilePath to a guaranteed non-existent file,
     *  so AuthLoader.loadAuthTokens() always throws IOException. */
    private static ServerConfig configWithNoAuth() {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.111.0",
                ServerConfig.DEFAULT_BASE_URL,
                null, null,
                "/nonexistent-path-that-cannot-exist/auth.json",
                "", false,
                Map.of(), null
        );
    }

    /**
     * AuthResult record: (accessToken, accountId, idToken, refreshToken, sourcePath, lastRefresh)
     */
    private static AuthLoader.AuthResult makeResult(String accessToken) {
        return new AuthLoader.AuthResult(accessToken, "acct-123", null, null, null, null);
    }

    /** Build a minimal 3-part JWT with only an "exp" claim. */
    private static String makeJwt(long expEpochSec) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + expEpochSec + "}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".sig";
    }

    private static void seedCurrent(AuthManager manager, AuthLoader.AuthResult result) throws Exception {
        Field f = AuthManager.class.getDeclaredField("current");
        f.setAccessible(true);
        f.set(manager, result);
    }

    @Test void freshToken_getAuthHeaders_returnsAllRequiredKeys() throws Exception {
        long futureExp = System.currentTimeMillis() / 1000 + 3600; // 1 hour from now
        AuthManager manager = new AuthManager(minimalConfig(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult(makeJwt(futureExp)));

        Map<String, String> headers = manager.getAuthHeaders();

        assertTrue(headers.containsKey("Authorization"),        "Missing Authorization header");
        assertTrue(headers.containsKey("chatgpt-account-id"),  "Missing chatgpt-account-id header");
        assertTrue(headers.containsKey("OpenAI-Beta"),         "Missing OpenAI-Beta header");
        assertTrue(headers.get("Authorization").startsWith("Bearer "),
                "Authorization should start with 'Bearer '");
        assertEquals("acct-123", headers.get("chatgpt-account-id"),
                "chatgpt-account-id should match seeded accountId");
    }

    @Test void expiringToken_getAuthHeaders_throwsIoException() throws Exception {
        // 240 s = 4 min, which is < REFRESH_EXPIRY_MARGIN_MS in AuthManager (5 min = 300 s)
        // → isTokenExpiringSoon() returns true → ensureFresh() called → IOException (no auth file)
        long nearExp = System.currentTimeMillis() / 1000 + 240;
        AuthManager manager = new AuthManager(configWithNoAuth(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult(makeJwt(nearExp)));
        assertThrows(IOException.class, manager::getAuthHeaders);
    }

    @Test void nullAccessToken_getAuthHeaders_throwsIoException() throws Exception {
        AuthManager manager = new AuthManager(configWithNoAuth(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult(null));
        assertThrows(IOException.class, manager::getAuthHeaders);
    }

    @Test void emptyAccessToken_getAuthHeaders_throwsIoException() throws Exception {
        AuthManager manager = new AuthManager(configWithNoAuth(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult(""));
        assertThrows(IOException.class, manager::getAuthHeaders);
    }

    // --- isTokenExpiringSoon boundary cases ---

    @Test void tokenAtExactMarginBoundary_isExpiringSoon() throws Exception {
        // exp == now + 300s (exactly REFRESH_EXPIRY_MARGIN_MS): condition is <=, so triggers refresh
        long atMargin = System.currentTimeMillis() / 1000 + 300;
        AuthManager manager = new AuthManager(configWithNoAuth(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult(makeJwt(atMargin)));
        assertThrows(IOException.class, manager::getAuthHeaders);
    }

    @Test void tokenJustOutsideMargin_isNotExpiringSoon() throws Exception {
        // exp == now + 301s: just outside the 300s margin, should not trigger refresh
        long justOutside = System.currentTimeMillis() / 1000 + 301;
        AuthManager manager = new AuthManager(minimalConfig(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult(makeJwt(justOutside)));
        Map<String, String> headers = manager.getAuthHeaders();
        assertTrue(headers.containsKey("Authorization"));
    }

    @Test void malformedJwt_treatedAsNotExpiring() throws Exception {
        // Non-JWT: JwtParser.parseClaims returns null → isTokenExpiringSoon returns false (no exp found)
        // Token is used as-is, same as a token with no exp claim
        AuthManager manager = new AuthManager(minimalConfig(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult("not-a-jwt-at-all"));
        Map<String, String> headers = manager.getAuthHeaders();
        assertTrue(headers.containsKey("Authorization"));
    }

    @Test void tokenWithNoExpClaim_getAuthHeaders_returnsHeadersWithoutRefresh() throws Exception {
        // isTokenExpiringSoon() returns false when JWT has no "exp" claim → token used as-is
        String noExpJwt = "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"user123\"}".getBytes(StandardCharsets.UTF_8)) + ".sig";
        AuthManager manager = new AuthManager(minimalConfig(), HttpClient.newHttpClient());
        seedCurrent(manager, makeResult(noExpJwt));

        Map<String, String> headers = manager.getAuthHeaders();
        assertTrue(headers.containsKey("Authorization"));
    }
}
