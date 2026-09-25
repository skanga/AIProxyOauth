package com.aiproxyoauth.util;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test
    void mapperIsAvailable() {
        assertNotNull(Json.MAPPER);
    }

    @Test
    void canParseJson() throws Exception {
        String json = "{\"key\":\"value\"}";
        JsonNode node = Json.MAPPER.readTree(json);
        assertEquals("value", node.get("key").asString());
    }
}
