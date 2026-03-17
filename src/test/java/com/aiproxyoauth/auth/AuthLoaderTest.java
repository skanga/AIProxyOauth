package com.aiproxyoauth.auth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadAuthTokens_validFile_noRefreshNeeded() throws Exception {
        Path authFile = tempDir.resolve("auth.json");
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode tokens = Json.MAPPER.createObjectNode();
        
        // Future expiry for access token
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        String payload = "{\"exp\":" + exp + "}";
        String accessToken = "header." + Base64.getUrlEncoder().encodeToString(payload.getBytes()) + ".signature";
        
        tokens.put("access_token", accessToken);
        tokens.put("account_id", "test-account");
        root.set("tokens", tokens);
        Files.writeString(authFile, Json.MAPPER.writeValueAsString(root));

        HttpClient mockClient = mock(HttpClient.class);
        AuthLoader.AuthResult result = AuthLoader.loadAuthTokens(
                authFile.toString(), "client-id", "https://issuer.com", "https://token.url", mockClient);

        assertEquals(accessToken, result.accessToken());
        assertEquals("test-account", result.accountId());
        verifyNoInteractions(mockClient);
    }

    @Test
    void loadAuthTokens_fileNotFound_throwsException() {
        HttpClient mockClient = mock(HttpClient.class);
        assertThrows(IOException.class, () -> {
            AuthLoader.loadAuthTokens(
                    tempDir.resolve("non-existent.json").toString(),
                    "client-id", "https://issuer.com", "https://token.url", mockClient);
        });
    }

    @Test
    void loadAuthTokens_missingAccessToken_throwsException() throws IOException {
        Path authFile = tempDir.resolve("auth.json");
        Files.writeString(authFile, "{\"tokens\":{\"account_id\":\"test-account\"}}");
        HttpClient mockClient = mock(HttpClient.class);

        assertThrows(IOException.class, () -> {
            AuthLoader.loadAuthTokens(
                    authFile.toString(), "client-id", "https://issuer.com", "https://token.url", mockClient);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadAuthTokens_refreshNeeded_success() throws Exception {
        Path authFile = tempDir.resolve("auth.json");
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode tokens = Json.MAPPER.createObjectNode();

        // Expired access token
        long exp = (System.currentTimeMillis() / 1000) - 3600;
        String payload = "{\"exp\":" + exp + "}";
        String oldAccessToken = "header." + Base64.getUrlEncoder().encodeToString(payload.getBytes()) + ".signature";

        tokens.put("access_token", oldAccessToken);
        tokens.put("refresh_token", "old-refresh-token");
        tokens.put("account_id", "test-account");
        root.set("tokens", tokens);
        Files.writeString(authFile, Json.MAPPER.writeValueAsString(root));

        // Mock HTTP response for refresh
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        
        ObjectNode refreshPayload = Json.MAPPER.createObjectNode();
        refreshPayload.put("access_token", "new-access-token");
        refreshPayload.put("refresh_token", "new-refresh-token");
        refreshPayload.put("id_token", "header." + Base64.getUrlEncoder().encodeToString("{\"sub\":\"new-account\"}".getBytes()) + ".signature");
        
        when(mockResponse.body()).thenReturn(Json.MAPPER.writeValueAsString(refreshPayload));
        when(mockClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        AuthLoader.AuthResult result = AuthLoader.loadAuthTokens(
                authFile.toString(), "client-id", "https://issuer.com", "https://token.url", mockClient);

        assertEquals("new-access-token", result.accessToken());
        assertEquals("new-refresh-token", result.refreshToken());
        
        // Verify file was updated
        String updatedContent = Files.readString(authFile);
        ObjectNode updatedJson = (ObjectNode) Json.MAPPER.readTree(updatedContent);
        assertEquals("new-access-token", updatedJson.get("tokens").get("access_token").asText());
    }
}
