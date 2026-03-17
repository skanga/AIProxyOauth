package com.aiproxyoauth.server;

import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.usage.UsageTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.aiproxyoauth.util.Json;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HandlersTest {

    @Mock Context ctx;
    @Mock ModelResolver modelResolver;
    @Mock UsageTracker usageTracker;

    @Test
    void healthHandler_returnsOk() {
        HealthHandler handler = new HealthHandler();
        handler.handle(ctx);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).status(200);
        verify(ctx).result(anyString());
    }

    @Test
    void modelsHandler_returnsModelList() throws Exception {
        when(modelResolver.resolveModels()).thenReturn(List.of("gpt-5"));
        ModelsHandler handler = new ModelsHandler(modelResolver);
        
        handler.handle(ctx);

        verify(ctx).status(200);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).result(resultCaptor.capture());
        
        JsonNode node = Json.MAPPER.readTree(resultCaptor.getValue());
        assertEquals("list", node.get("object").asText());
        assertEquals("gpt-5", node.get("data").get(0).get("id").asText());
    }

    @Test
    void usageHandler_returnsUsageStats() throws Exception {
        UsageTracker tracker = new UsageTracker();
        tracker.record("test-key", 10, 5);
        UsageHandler handler = new UsageHandler(tracker);
        
        handler.handle(ctx);

        verify(ctx).status(200);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).result(resultCaptor.capture());
        
        JsonNode node = Json.MAPPER.readTree(resultCaptor.getValue());
        assertEquals(10, node.get("total").get("prompt_tokens").asInt());
        assertEquals(5, node.get("total").get("completion_tokens").asInt());
        assertEquals(15, node.get("total").get("total_tokens").asInt());
    }
}
