package com.aiproxyoauth.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponsesStateTest {

    @Test
    void requiresCachedState_detectsPreviousResponseId() {
        ResponsesState state = new ResponsesState();
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("previous_response_id", "abc");
        assertTrue(state.requiresCachedState(body));
    }

    @Test
    void requiresCachedState_detectsItemReference() {
        ResponsesState state = new ResponsesState();
        ObjectNode body = Json.MAPPER.createObjectNode();
        ArrayNode input = Json.MAPPER.createArrayNode();
        ObjectNode item = Json.MAPPER.createObjectNode();
        item.put("type", "item_reference");
        item.put("id", "item-123");
        input.add(item);
        body.set("input", input);
        assertTrue(state.requiresCachedState(body));
    }

    @Test
    void requiresCachedState_falseForNormalRequest() {
        ResponsesState state = new ResponsesState();
        ObjectNode body = Json.MAPPER.createObjectNode();
        ArrayNode input = Json.MAPPER.createArrayNode();
        body.set("input", input);
        assertFalse(state.requiresCachedState(body));
    }

    // --- expandInput (via expandRequestBody) ---

    @Test
    void expandInput_replacesItemReferenceWithCachedItem() {
        ResponsesState state = new ResponsesState();

        // Teach the cache about item-1 via rememberResponse
        ObjectNode response = Json.MAPPER.createObjectNode();
        response.put("id", "resp-1");
        ArrayNode output = Json.MAPPER.createArrayNode();
        ObjectNode cachedItem = Json.MAPPER.createObjectNode();
        cachedItem.put("id", "item-1");
        cachedItem.put("type", "message");
        cachedItem.put("role", "assistant");
        output.add(cachedItem);
        response.set("output", output);
        state.rememberResponse(response, Json.MAPPER.createObjectNode());

        // Build a request with an item_reference pointing to item-1
        ObjectNode body = Json.MAPPER.createObjectNode();
        ArrayNode input = Json.MAPPER.createArrayNode();
        ObjectNode ref = Json.MAPPER.createObjectNode();
        ref.put("type", "item_reference");
        ref.put("id", "item-1");
        input.add(ref);
        body.set("input", input);

        ObjectNode expanded = state.expandRequestBody(body);

        JsonNode expandedInput = expanded.get("input");
        assertEquals(1, expandedInput.size());
        // item_reference replaced with the full cached item
        assertEquals("message", expandedInput.get(0).path("type").asText());
        assertEquals("assistant", expandedInput.get(0).path("role").asText());
    }

    @Test
    void expandInput_unknownItemReference_passedThrough() {
        ResponsesState state = new ResponsesState();

        ObjectNode body = Json.MAPPER.createObjectNode();
        ArrayNode input = Json.MAPPER.createArrayNode();
        ObjectNode ref = Json.MAPPER.createObjectNode();
        ref.put("type", "item_reference");
        ref.put("id", "no-such-item");
        input.add(ref);
        body.set("input", input);

        ObjectNode expanded = state.expandRequestBody(body);

        // Unknown reference left as-is (not dropped, not expanded)
        JsonNode expandedInput = expanded.get("input");
        assertEquals(1, expandedInput.size());
        assertEquals("item_reference", expandedInput.get(0).path("type").asText());
        assertEquals("no-such-item", expandedInput.get(0).path("id").asText());
    }

    @Test
    void expandInput_mixedItems_onlyReferencesExpanded() {
        ResponsesState state = new ResponsesState();

        // Cache one item
        ObjectNode response = Json.MAPPER.createObjectNode();
        response.put("id", "resp-1");
        ArrayNode output = Json.MAPPER.createArrayNode();
        ObjectNode cachedItem = Json.MAPPER.createObjectNode();
        cachedItem.put("id", "item-cached");
        cachedItem.put("type", "message");
        output.add(cachedItem);
        response.set("output", output);
        state.rememberResponse(response, Json.MAPPER.createObjectNode());

        // Input: regular item, item_reference, another regular item
        ObjectNode body = Json.MAPPER.createObjectNode();
        ArrayNode input = Json.MAPPER.createArrayNode();
        ObjectNode regular1 = Json.MAPPER.createObjectNode();
        regular1.put("type", "message");
        regular1.put("role", "user");
        input.add(regular1);
        ObjectNode ref = Json.MAPPER.createObjectNode();
        ref.put("type", "item_reference");
        ref.put("id", "item-cached");
        input.add(ref);
        ObjectNode regular2 = Json.MAPPER.createObjectNode();
        regular2.put("type", "message");
        regular2.put("role", "user");
        input.add(regular2);
        body.set("input", input);

        ObjectNode expanded = state.expandRequestBody(body);

        JsonNode expandedInput = expanded.get("input");
        assertEquals(3, expandedInput.size());
        assertEquals("user", expandedInput.get(0).path("role").asText());   // regular1 unchanged
        assertEquals("message", expandedInput.get(1).path("type").asText()); // ref expanded to cached item
        assertFalse(expandedInput.get(1).has("role"));                        // cached item has no role
        assertEquals("user", expandedInput.get(2).path("role").asText());   // regular2 unchanged
    }

    @Test
    void expandRequestBody_remembersAndExpands() {
        ResponsesState state = new ResponsesState();
        
        ObjectNode response = Json.MAPPER.createObjectNode();
        response.put("id", "resp-1");
        ArrayNode output = Json.MAPPER.createArrayNode();
        ObjectNode outItem = Json.MAPPER.createObjectNode();
        outItem.put("id", "item-1");
        outItem.put("type", "message");
        output.add(outItem);
        response.set("output", output);

        ObjectNode request = Json.MAPPER.createObjectNode();
        ArrayNode input = Json.MAPPER.createArrayNode();
        ObjectNode inItem = Json.MAPPER.createObjectNode();
        inItem.put("type", "message");
        inItem.put("role", "user");
        input.add(inItem);
        request.set("input", input);

        state.rememberResponse(response, request);

        ObjectNode nextRequest = Json.MAPPER.createObjectNode();
        nextRequest.put("previous_response_id", "resp-1");
        
        ObjectNode expanded = state.expandRequestBody(nextRequest);
        
        assertFalse(expanded.has("previous_response_id"));
        assertTrue(expanded.has("input"));
        assertEquals(2, expanded.get("input").size()); // 1 from original input + 1 from output
    }
}
