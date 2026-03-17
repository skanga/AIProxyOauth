package com.aiproxyoauth.sse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SseParserTest {
    @Test
    void parse_singleEvent() throws Exception {
        String data = "event: message\ndata: Hello\n\n";
        InputStream is = new ByteArrayInputStream(data.getBytes());
        List<ServerSentEvent> events = SseParser.parse(is);
        
        assertEquals(1, events.size());
        assertEquals("message", events.getFirst().event());
        assertEquals("Hello", events.getFirst().data());
    }

    @Test
    void parse_multipleEvents() throws Exception {
        String data = "data: first\n\ndata: second\n\n";
        InputStream is = new ByteArrayInputStream(data.getBytes());
        List<ServerSentEvent> events = SseParser.parse(is);
        
        assertEquals(2, events.size());
        assertEquals("first", events.get(0).data());
        assertEquals("second", events.get(1).data());
    }

    @Test
    void parse_multiLineData() throws Exception {
        String data = "data: line1\ndata: line2\n\n";
        InputStream is = new ByteArrayInputStream(data.getBytes());
        List<ServerSentEvent> events = SseParser.parse(is);
        
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.getFirst().data());
    }
}
