package com.aiproxyoauth.config;

import com.aiproxyoauth.provider.copilot.CopilotCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CopilotCredentialsTest {
    @Test void managedStoreAndExplicitManagedFileRejectExpiredTokens() throws Exception {
        var credentials = new CopilotCredentials(config(null, null));
        credentials.save(com.aiproxyoauth.util.Json.MAPPER.readTree("{\"access_token\":\"saved-secret\"}"));
        assertEquals("saved-secret", credentials.token());
        Path path = config(null, null).oauthFile();
        var expired = (com.fasterxml.jackson.databind.node.ObjectNode) com.aiproxyoauth.util.Json.MAPPER.readTree(Files.readString(path));
        expired.put("expires_at", "2020-01-01T00:00:00Z");
        Files.writeString(path, expired.toString());
        assertThrows(IOException.class, credentials::token);
        assertThrows(IOException.class, () -> new CopilotCredentials(config(path, "fallback-secret")).token());
        credentials.logout();
        assertFalse(Files.exists(path));
    }
    @TempDir Path temporary;
    EffectiveConfig.Copilot config(Path tokenFile, String environment) {
        return new EffectiveConfig.Copilot("github.com", temporary.resolve("auth.json"), "client", tokenFile, environment, List.of());
    }
    @Test void explicitFileWinsAndInvalidFileNeverFallsBack() throws Exception {
        Path file = temporary.resolve("token.txt");
        Files.writeString(file, "file-token\n");
        assertEquals("file-token", new CopilotCredentials(config(file, "env-token")).token());
        Files.writeString(file, "");
        assertThrows(IOException.class, () -> new CopilotCredentials(config(file, "env-token")).token());
    }
    @Test void usesEnvironmentAndDoesNotLeakInConfigDisplay() throws Exception {
        var config = config(null, "env-secret");
        assertEquals("env-secret", new CopilotCredentials(config).token());
        assertFalse(config.toString().contains("env-secret"));
    }
    @Test void explicitCliConfigIsReadOnlyAndHostBound() throws Exception {
        Path file = temporary.resolve("config.json");
        String original = """
                // external CLI credential
                {"lastLoggedInUser":{"host":"https://github.com","login":"example"},
                "authTokens":{"https://github.com:example":{"token":"cli-secret"}}}
                """;
        Files.writeString(file, original);
        assertEquals("cli-secret", new CopilotCredentials(config(file, null)).token());
        assertEquals(original, Files.readString(file));
        var other = new EffectiveConfig.Copilot("acme.ghe.com", file, "client", file, null, List.of());
        assertThrows(IOException.class, () -> new CopilotCredentials(other).token());
    }
}
