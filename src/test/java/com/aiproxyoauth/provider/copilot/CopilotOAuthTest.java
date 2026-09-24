package com.aiproxyoauth.provider.copilot;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class CopilotOAuthTest {
    @Test void pendingAndSlowDownRespectPollingAndNeverPrintToken() throws Exception {
        var results = new ArrayDeque<String>(List.of(
                "{\"device_code\":\"private-code\",\"user_code\":\"ABCD-1234\",\"verification_uri\":\"https://github.com/login/device\",\"interval\":5,\"expires_in\":300}",
                "{\"error\":\"authorization_pending\"}", "{\"error\":\"slow_down\"}", "{\"access_token\":\"private-token\"}"));
        AtomicLong time = new AtomicLong(); List<Long> waits = new ArrayList<>(); StringWriter output = new StringWriter();
        var token = CopilotOAuth.authorize("github.com", "client", (path, body) -> {
            assertFalse(body.has("scope")); return Json.MAPPER.readTree(results.remove());
        }, new PrintWriter(output), millis -> { waits.add(millis); time.addAndGet(millis * 1000000); }, time::get);
        assertEquals("private-token", token.path("access_token").asText());
        assertEquals(List.of(5000L, 5000L, 10000L), waits);
        assertFalse(output.toString().contains("private-"));
    }
    @Test void refusesCrossTenantVerificationAndHonorsCancellation() throws Exception {
        String start = "{\"device_code\":\"private-code\",\"user_code\":\"ABCD-1234\",\"verification_uri\":\"https://github.com/login/device\",\"interval\":5,\"expires_in\":300}";
        assertThrows(IOException.class, () -> CopilotOAuth.authorize("acme.ghe.com", "client", (p,b) -> Json.MAPPER.readTree(start),
                new PrintWriter(new StringWriter()), millis -> {}, () -> 0));
        assertThrows(InterruptedException.class, () -> CopilotOAuth.authorize("github.com", "client", (p,b) -> Json.MAPPER.readTree(start),
                new PrintWriter(new StringWriter()), millis -> { throw new InterruptedException(); }, () -> 0));
    }
}
