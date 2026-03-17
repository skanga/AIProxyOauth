package com.aiproxyoauth.transport;

import com.aiproxyoauth.auth.AuthManager;
import com.aiproxyoauth.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodexHttpClientTest {

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
}
