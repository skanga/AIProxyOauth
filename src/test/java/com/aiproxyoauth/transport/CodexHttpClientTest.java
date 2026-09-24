package com.aiproxyoauth.transport;

import com.aiproxyoauth.auth.AuthManager;
import com.aiproxyoauth.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodexHttpClientTest {

    @Test @SuppressWarnings("unchecked")
    void inferenceAndStringRequestsHaveDeadlinesAndProductionClientBoundsConnections() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 10531, null, "0.1", "http://base", null, null, null, "", false, Map.of(), null);
        when(authManager.getAuthHeaders()).thenReturn(Map.of());
        HttpResponse<?> response = mock(HttpResponse.class);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new CodexHttpClient(config, httpClient, authManager);
        client.request("/responses", "POST", "{}", Map.of());
        client.requestString("/models", "GET", null, Map.of());
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        for (HttpRequest request : requests.getAllValues()) {
            assertTrue(request.timeout().isPresent(), "Every upstream request needs a deadline");
            assertTrue(request.timeout().orElseThrow().compareTo(java.time.Duration.ZERO) > 0);
            assertTrue(request.timeout().orElseThrow().compareTo(java.time.Duration.ofMinutes(2)) <= 0);
        }
        try (HttpClient production = new CodexHttpClient(config, authManager).getHttpClient()) {
            assertTrue(production.connectTimeout().isPresent());
            assertTrue(production.connectTimeout().orElseThrow().compareTo(java.time.Duration.ofSeconds(15)) <= 0);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void distinguishesNetworkIoFromCredentialFailureAndCancellation() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 10531, null, "0.1", "http://base", null, null, null, "", false, Map.of(), null);
        when(authManager.getAuthHeaders()).thenReturn(Map.of());
        var client = new CodexHttpClient(config, httpClient, authManager);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("connection reset"));
        assertThrows(java.net.ConnectException.class, () -> client.request("/test", "POST", "{}", Map.of()));
        when(authManager.getAuthHeaders()).thenThrow(new java.io.IOException("credential unavailable"));
        var error = assertThrows(java.io.IOException.class, () -> client.request("/test", "POST", "{}", Map.of()));
        assertFalse(error instanceof java.net.ConnectException);
        doReturn(Map.of()).when(authManager).getAuthHeaders();
        doThrow(new InterruptedException()).when(httpClient).send(any(), any(HttpResponse.BodyHandler.class));
        assertThrows(InterruptedException.class, () -> client.request("/test", "POST", "{}", Map.of()));
    }

    @Mock HttpClient httpClient;
    @Mock AuthManager authManager;

    @Test
    @SuppressWarnings("unchecked")
    void requestString_success() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 10531, null, "0.1", "http://base", null, null, null, "", false, Map.of(), null);
        when(authManager.getAuthHeaders()).thenReturn(Map.of("Authorization", "Bearer token"));
        
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("ok");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        CodexHttpClient codexClient = new CodexHttpClient(config, httpClient, authManager);
        HttpResponse<String> resp = codexClient.requestString("/test", "GET", null, Map.of());
        
        assertEquals(200, resp.statusCode());
        assertEquals("ok", resp.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    void request_success() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 10531, null, "0.1", "http://base", null, null, null, "", false, Map.of(), null);
        when(authManager.getAuthHeaders()).thenReturn(Map.of("Authorization", "Bearer token"));
        
        HttpResponse<java.io.InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(new java.io.ByteArrayInputStream("ok".getBytes()));
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        CodexHttpClient codexClient = new CodexHttpClient(config, httpClient, authManager);
        HttpResponse<java.io.InputStream> resp = codexClient.request("/test", "POST", "body", Map.of("X-Extra", "value"));
        
        assertEquals(200, resp.statusCode());
        assertNotNull(resp.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    void request_withPromptCacheKey_addsConversationHeaders() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 10531, null, "0.1", "http://base", null, null, null, "", false, Map.of(), null);
        when(authManager.getAuthHeaders()).thenReturn(Map.of("Authorization", "Bearer token"));

        HttpResponse<java.io.InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        CodexHttpClient codexClient = new CodexHttpClient(config, httpClient, authManager);
        codexClient.request("/test", "POST", "body", Map.of("X-Extra", "value"), "req_test", "cache-123");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        assertEquals("cache-123", request.headers().firstValue("conversation_id").orElseThrow());
        assertEquals("cache-123", request.headers().firstValue("session_id").orElseThrow());
    }
}
