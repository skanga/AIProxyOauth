package com.aiproxyoauth.model;
import com.aiproxyoauth.provider.copilot.CopilotClient;
import com.aiproxyoauth.server.OpenAiChatRequestAdapter;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CopilotModelCatalogTest {
    @Test void expiresStaleCatalogAndNeverCarriesItAcrossAccounts() throws Exception {
        CopilotClient client = mock(CopilotClient.class); Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-09-23T00:00:00Z");
        when(clock.instant()).thenReturn(start); when(client.identity()).thenReturn("account1");
        when(client.models()).thenReturn(Json.MAPPER.readTree("{\"data\":[{\"id\":\"test\"}]}"));
        var catalog = new CopilotModelCatalog(client, List.of(), clock);
        assertEquals(1, catalog.resolveModels().size());
        when(client.models()).thenThrow(new IOException("offline"));
        when(clock.instant()).thenReturn(start.plusSeconds(301));
        assertEquals(1, catalog.resolveModels().size());
        when(clock.instant()).thenReturn(start.plusSeconds(3601));
        assertThrows(IOException.class, catalog::resolveModels);
        when(clock.instant()).thenReturn(start.plusSeconds(302)); when(client.identity()).thenReturn("account2");
        assertThrows(IOException.class, catalog::resolveModels);
    }
    @Test void rejectsImagesExceedingAdvertisedLimitsAndNonStreamingModels() throws Exception {
        CopilotClient client = mock(CopilotClient.class); when(client.identity()).thenReturn("account");
        when(client.models()).thenReturn(Json.MAPPER.readTree("""
                {"data":[{"id":"test","capabilities":{"supports":{"vision":true,"streaming":true},
                "limits":{"vision":{"max_prompt_images":1,"max_prompt_image_size":1,"supported_media_types":["image/png"]}}}},
                {"id":"sync","capabilities":{"supports":{"streaming":false}}}]}
                """));
        var catalog = new CopilotModelCatalog(client, List.of(), Clock.systemUTC());
        var adapter = new OpenAiChatRequestAdapter();
        var image = adapter.adapt(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}]}]}
                """), "test");
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(image));
        var request = adapter.adapt(Json.MAPPER.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"), "sync");
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(request));
    }
}
