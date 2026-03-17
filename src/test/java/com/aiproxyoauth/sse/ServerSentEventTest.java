package com.aiproxyoauth.sse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerSentEventTest {
    @Test
    void testGetters() {
        ServerSentEvent event = new ServerSentEvent("event", "data");
        assertEquals("event", event.event());
        assertEquals("data", event.data());
    }
}
