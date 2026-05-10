package com.aiproxyoauth.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamErrorMapperTest {

    private final UpstreamErrorMapper mapper = new UpstreamErrorMapper();

    @Test
    void map_404UsageLimitCodes_become429() {
        assertStatus(429, 404, "{\"error\":{\"code\":\"usage_limit_reached\",\"message\":\"limit\"}}");
        assertStatus(429, 404, "{\"error\":{\"code\":\"usage_not_included\",\"message\":\"limit\"}}");
        assertStatus(429, 404, "{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"limit\"}}");
    }

    @Test
    void map_404UsageLimitText_becomes429() {
        assertStatus(429, 404, "{\"error\":{\"message\":\"Your usage limit has been reached\"}}");
        assertStatus(429, 404, "usage limit reached");
    }

    @Test
    void map_nonUsage404Remains404() {
        assertStatus(404, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"missing\"}}");
    }

    @Test
    void map_non404UsageLimitRemainsOriginalStatus() {
        assertStatus(400, 400, "{\"error\":{\"code\":\"usage_limit_reached\",\"message\":\"limit\"}}");
    }

    @Test
    void map_existingJsonErrorShapeIsPreserved() {
        String body = "{\"error\":{\"code\":\"usage_limit_reached\",\"message\":\"limit\"}}";

        UpstreamErrorMapper.MappedUpstreamError mapped = mapper.map(404, body);

        assertEquals(429, mapped.statusCode());
        assertEquals(body, mapped.body());
    }

    @Test
    void map_plainTextBodyIsWrappedByJsonHelperUsingMappedStatus() {
        UpstreamErrorMapper.MappedUpstreamError mapped = mapper.map(404, "usage limit reached");

        assertEquals(429, mapped.statusCode());
        assertTrue(mapped.body().contains("usage limit reached"));
        assertTrue(mapped.body().contains("\"429\""));
    }

    private void assertStatus(int expected, int upstreamStatus, String body) {
        assertEquals(expected, mapper.map(upstreamStatus, body).statusCode());
    }
}
