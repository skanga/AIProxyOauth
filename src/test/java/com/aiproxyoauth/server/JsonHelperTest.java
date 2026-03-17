package com.aiproxyoauth.server;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonHelperTest {

    // --- mapFinishReason ---

    @Test void mapFinishReason_stop() {
        assertEquals("stop", JsonHelper.mapFinishReason("stop"));
    }

    @Test void mapFinishReason_length() {
        assertEquals("length", JsonHelper.mapFinishReason("length"));
    }

    @Test void mapFinishReason_maxOutputTokens() {
        assertEquals("length", JsonHelper.mapFinishReason("max_output_tokens"));
    }

    @Test void mapFinishReason_toolCalls_underscore() {
        assertEquals("tool_calls", JsonHelper.mapFinishReason("tool_calls"));
    }

    @Test void mapFinishReason_toolCalls_hyphen() {
        assertEquals("tool_calls", JsonHelper.mapFinishReason("tool-calls"));
    }

    @Test void mapFinishReason_contentFilter_underscore() {
        assertEquals("content_filter", JsonHelper.mapFinishReason("content_filter"));
    }

    @Test void mapFinishReason_contentFilter_hyphen() {
        assertEquals("content_filter", JsonHelper.mapFinishReason("content-filter"));
    }

    @Test void mapFinishReason_null_returnsNull() {
        assertNull(JsonHelper.mapFinishReason(null));
    }

    @Test void mapFinishReason_unknown_returnsNull() {
        assertNull(JsonHelper.mapFinishReason("something_unknown"));
    }

    // --- toUsage ---

    @Test void toUsage_nullInput_returnsZeros() {
        ObjectNode usage = JsonHelper.toUsage(null);
        assertEquals(0, usage.path("prompt_tokens").asInt());
        assertEquals(0, usage.path("completion_tokens").asInt());
        assertEquals(0, usage.path("total_tokens").asInt());
    }

    @Test void toUsage_populatedNode_mapsCorrectly() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("input_tokens", 10);
        node.put("output_tokens", 5);
        ObjectNode usage = JsonHelper.toUsage(node);
        assertEquals(10, usage.path("prompt_tokens").asInt());
        assertEquals(5, usage.path("completion_tokens").asInt());
        assertEquals(15, usage.path("total_tokens").asInt());
    }

    @Test void toUsage_cachedTokensPresent_promptDetailsIncluded() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("input_tokens", 10);
        node.put("output_tokens", 5);
        ObjectNode inputDetails = Json.MAPPER.createObjectNode();
        inputDetails.put("cached_tokens", 3);
        node.set("input_tokens_details", inputDetails);
        ObjectNode usage = JsonHelper.toUsage(node);
        assertEquals(3, usage.path("prompt_tokens_details").path("cached_tokens").asInt());
    }

    @Test void toUsage_noCachedTokens_promptDetailsAbsent() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("input_tokens", 10);
        node.put("output_tokens", 5);
        ObjectNode usage = JsonHelper.toUsage(node);
        assertFalse(usage.has("prompt_tokens_details"));
    }

    @Test void toUsage_reasoningTokensPresent_completionDetailsIncluded() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("input_tokens", 10);
        node.put("output_tokens", 5);
        ObjectNode outputDetails = Json.MAPPER.createObjectNode();
        outputDetails.put("reasoning_tokens", 2);
        node.set("output_tokens_details", outputDetails);
        ObjectNode usage = JsonHelper.toUsage(node);
        assertEquals(2, usage.path("completion_tokens_details").path("reasoning_tokens").asInt());
    }

    // --- toUpstreamErrorBody ---

    @Test void toUpstreamErrorBody_validJsonObject_returnedAsIs() {
        String json = "{\"error\":{\"message\":\"bad request\"}}";
        assertEquals(json, JsonHelper.toUpstreamErrorBody(json, 400));
    }

    @Test void toUpstreamErrorBody_plainText_wrappedWithStatusCode() {
        String result = JsonHelper.toUpstreamErrorBody("something went wrong", 503);
        assertTrue(result.contains("\"something went wrong\""),
                "Expected message in body: " + result);
        assertTrue(result.contains("\"503\""),
                "Expected status code as string in body: " + result);
    }

    @Test void toUpstreamErrorBody_blankInput_defaultMessageWithStatusCode() {
        String result = JsonHelper.toUpstreamErrorBody("  ", 500);
        assertTrue(result.contains("Upstream error"),
                "Expected default message in body: " + result);
        assertTrue(result.contains("\"500\""),
                "Expected status code as string in body: " + result);
    }
}
