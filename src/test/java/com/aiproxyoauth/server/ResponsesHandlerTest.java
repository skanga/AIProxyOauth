package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponsesHandlerTest {

    @Mock CodexHttpClient client;
    @Mock Context ctx;
    @Mock UsageTracker usageTracker;

    private static ServerConfig minimalConfig() {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.111.0",
                ServerConfig.DEFAULT_BASE_URL,
                null, null, null,
                "instr", false,
                Map.of(), null
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void handle_previousResponseId_accepted_andExpanded() throws Exception {
        // previous_response_id with no cached history: handler forwards the request as-is
        // (expansion is a no-op when nothing is cached for that id)
        when(ctx.body()).thenReturn("{\"previous_response_id\":\"unknown-id\",\"input\":[]}");

        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        String sseData = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n";
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream(sseData.getBytes()));
        when(client.request(eq("/responses"), eq("POST"), anyString(), any())).thenReturn(mockResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);

        // No 400 — request forwarded successfully
        verify(ctx).status(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void handle_nonStreaming_success() throws Exception {
        when(ctx.body()).thenReturn("{\"input\":[]}");
        
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        String sseData = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}\n\n";
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream(sseData.getBytes()));
        
        when(client.request(eq("/responses"), eq("POST"), anyString(), any())).thenReturn(mockResponse);
        
        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);

        verify(ctx).status(200);
        verify(usageTracker).record(any(), eq(5L), eq(2L));
    }
}
