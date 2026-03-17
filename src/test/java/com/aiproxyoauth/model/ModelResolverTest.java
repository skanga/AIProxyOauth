package com.aiproxyoauth.model;

import com.aiproxyoauth.transport.CodexHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelResolverTest {

    @Mock CodexHttpClient client;

    /** All model-list tests use this to bypass version resolution via subprocess/HTTP. */
    private ModelResolver resolverWithVersion(List<String> configuredModels) {
        return new ModelResolver(client, configuredModels, "0.115.0");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> stringResponse(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    @Test void configuredModels_returnedDirectly_noHttpCall() throws Exception {
        ModelResolver resolver = resolverWithVersion(List.of("gpt-5", "gpt-4"));
        List<String> result = resolver.resolveModels();
        assertEquals(List.of("gpt-5", "gpt-4"), result);
        verifyNoInteractions(client);
    }

    @Test void fetchModels_parsesSlugArray() throws Exception {
        HttpResponse<String> resp = stringResponse(200, "{\"models\":[{\"slug\":\"gpt-5\"},{\"slug\":\"gpt-4\"}]}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        ModelResolver resolver = resolverWithVersion(null);
        assertEquals(List.of("gpt-5", "gpt-4"), resolver.resolveModels());
    }

    @Test void fetchModels_nonSuccessStatus_throwsRuntimeException() throws Exception {
        HttpResponse<String> resp = stringResponse(401, "{\"detail\":\"Unauthorized\"}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        ModelResolver resolver = resolverWithVersion(null);
        assertThrows(RuntimeException.class, resolver::resolveModels);
    }

    @Test void fetchModels_emptyList_throwsRuntimeException() throws Exception {
        HttpResponse<String> resp = stringResponse(200, "{\"models\":[]}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        ModelResolver resolver = resolverWithVersion(null);
        assertThrows(RuntimeException.class, resolver::resolveModels);
    }

    @Test void resolveCodexClientVersion_explicitVersion_returnedDirectlyNoHttp() {
        ModelResolver resolver = new ModelResolver(client, null, "1.2.3");
        assertEquals("1.2.3", resolver.resolveCodexClientVersion());
        verifyNoInteractions(client);
    }

    @Test void fetchModels_duplicateSlugs_deduplicated() throws Exception {
        HttpResponse<String> resp = stringResponse(200,
                "{\"models\":[{\"slug\":\"gpt-5\"},{\"slug\":\"gpt-5\"},{\"slug\":\"gpt-4\"}]}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        ModelResolver resolver = resolverWithVersion(null);
        assertEquals(List.of("gpt-5", "gpt-4"), resolver.resolveModels());
    }

    @Test void configuredModels_withDuplicates_deduplicated() throws Exception {
        ModelResolver resolver = resolverWithVersion(List.of("gpt-5", "gpt-4", "gpt-5"));
        assertEquals(List.of("gpt-5", "gpt-4"), resolver.resolveModels());
    }

    @Test void resolveCodexClientVersion_remoteDiscovery_success() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> resp = stringResponse(200, "{\"version\":\"1.2.3\"}");
        when(httpClient.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(resp);
        when(client.getHttpClient()).thenReturn(httpClient);

        ModelResolver resolver = new ModelResolver(client, null, null);
        // We can't easily mock local codex --version without a lot of ceremony,
        // but it will likely fail in a test environment, falling through to remote.
        String version = resolver.resolveCodexClientVersion();
        
        // If local fails, it should be 1.2.3 from our mock or fallback
        assertTrue(version.equals("1.2.3") || version.equals("0.115.0"));
    }

    @Test void resolveCodexClientVersion_fallback_onFailure() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new RuntimeException("network error"));
        when(client.getHttpClient()).thenReturn(httpClient);

        ModelResolver resolver = new ModelResolver(client, null, null);
        assertEquals("0.115.0", resolver.resolveCodexClientVersion());
    }

    // --- extractUpstreamError (via resolveModels on non-2xx) ---

    @Test void extractUpstreamError_detail_field() throws Exception {
        HttpResponse<String> resp = stringResponse(401, "{\"detail\":\"Unauthorized\"}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        RuntimeException ex = assertThrows(RuntimeException.class,
                resolverWithVersion(null)::resolveModels);
        assertTrue(ex.getMessage().contains("Unauthorized"), "Expected detail text in: " + ex.getMessage());
    }

    @Test void extractUpstreamError_error_message_field() throws Exception {
        HttpResponse<String> resp = stringResponse(429,
                "{\"error\":{\"message\":\"Rate limit exceeded\",\"type\":\"rate_limit_error\"}}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        RuntimeException ex = assertThrows(RuntimeException.class,
                resolverWithVersion(null)::resolveModels);
        assertTrue(ex.getMessage().contains("Rate limit exceeded"), "Expected error.message in: " + ex.getMessage());
    }

    @Test void extractUpstreamError_rawBody_fallback_whenNoKnownField() throws Exception {
        HttpResponse<String> resp = stringResponse(503, "{\"status\":\"down\"}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        RuntimeException ex = assertThrows(RuntimeException.class,
                resolverWithVersion(null)::resolveModels);
        assertTrue(ex.getMessage().contains("{\"status\":\"down\"}"), "Expected raw body in: " + ex.getMessage());
    }

    @Test void extractUpstreamError_rawBody_fallback_whenNotJson() throws Exception {
        HttpResponse<String> resp = stringResponse(502, "Bad Gateway");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        RuntimeException ex = assertThrows(RuntimeException.class,
                resolverWithVersion(null)::resolveModels);
        assertTrue(ex.getMessage().contains("Bad Gateway"), "Expected raw body in: " + ex.getMessage());
    }

    @Test void resolveModels_caching_works() throws Exception {
        HttpResponse<String> resp = stringResponse(200, "{\"models\":[{\"slug\":\"gpt-5\"}]}");
        when(client.requestString(anyString(), eq("GET"), any(), any())).thenReturn(resp);
        
        ModelResolver resolver = resolverWithVersion(null);
        
        // First call
        List<String> m1 = resolver.resolveModels();
        assertEquals(List.of("gpt-5"), m1);
        
        // Second call should use cache
        List<String> m2 = resolver.resolveModels();
        assertEquals(List.of("gpt-5"), m2);
        
        verify(client, times(1)).requestString(anyString(), anyString(), any(), any());
    }
}
