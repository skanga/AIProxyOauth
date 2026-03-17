package com.aiproxyoauth;

import com.aiproxyoauth.util.ApiKeyUtils;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class AIProxyOauthTest {

    private static final PrintStream NULL_STREAM = new PrintStream(OutputStream.nullOutputStream());

    @Test
    void testHelp() {
        AIProxyOauth app = new AIProxyOauth();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));
        
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("AIProxyOauth"), "Output was: " + output);
    }

    @Test
    void testVersion() {
        AIProxyOauth app = new AIProxyOauth();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));
        
        int exitCode = cmd.execute("--version");
        assertEquals(0, exitCode);
        assertTrue(sw.toString().contains("AIProxyOauth 1.0.0"));
    }

    @Test
    void testGenerateKey() {
        AIProxyOauth app = new AIProxyOauth();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));
        
        int exitCode = cmd.execute("--generate-key");
        assertEquals(0, exitCode);
        String output = sw.toString().trim();
        assertTrue(output.startsWith("sk-proxy-"));
        assertEquals(41, output.length());
    }

    @Test
    void testGenerateNamedKey() {
        AIProxyOauth app = new AIProxyOauth();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--generate-key", "myapp");
        assertEquals(0, exitCode);
        String output = sw.toString().trim();
        assertTrue(output.startsWith("myapp:sk-proxy-"));
    }

    @Test
    void testFindExistingAuthFile() {
        String result = AIProxyOauth.findExistingAuthFile("test");
        assertNull(result);
    }

    @Test
    void testParseKeyEntry() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        
        // Bare key
        ApiKeyUtils.parseKeyEntry("sk-123", map);
        assertEquals("sk-123", map.get("sk-123"));
        
        // Name:Key
        ApiKeyUtils.parseKeyEntry("myapp:sk-456", map);
        assertEquals("myapp", map.get("sk-456"));
        
        // With whitespace
        ApiKeyUtils.parseKeyEntry("  otherapp :  sk-789  ", map);
        assertEquals("otherapp", map.get("sk-789"));
        
        // Invalid (empty name or key)
        int sizeBefore = map.size();
        ApiKeyUtils.parseKeyEntry(":", map);
        ApiKeyUtils.parseKeyEntry("name:", map);
        ApiKeyUtils.parseKeyEntry(":key", map);
        assertEquals(sizeBefore, map.size());
    }

    @Test
    void testParseModelList() {
        AIProxyOauth app = new AIProxyOauth();
        CommandLine cmd = new CommandLine(app);
        
        // Use reflection or package-private access to set private fields for testing buildServerConfig
        // But for parseModelList we can test it directly if we set the field
        cmd.parseArgs("--models", "gpt-4, gpt-3.5-turbo , , gpt-4o");
        java.util.List<String> models = app.parseModelList();
        assertEquals(java.util.List.of("gpt-4", "gpt-3.5-turbo", "gpt-4o"), models);
    }

    @Test
    void testBuildServerConfig() throws Exception {
        AIProxyOauth app = new AIProxyOauth();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("--host", "0.0.0.0", "--port", "9000", "--models", "m1,m2", "--admin-key", "adm");
        
        com.aiproxyoauth.config.ServerConfig config = app.buildServerConfig();
        assertEquals("0.0.0.0", config.host());
        assertEquals(9000, config.port());
        assertEquals(java.util.List.of("m1", "m2"), config.models());
        assertEquals("adm", config.adminKey());
    }

    @Test
    void testBuildServerConfig_AdminInFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        AIProxyOauth app = new AIProxyOauth();
        CommandLine cmd = new CommandLine(app);
        
        java.nio.file.Path keysFile = tempDir.resolve("keys.txt");
        java.nio.file.Files.writeString(keysFile, "admin:sk-admin-123\nuser1:sk-user-123\n");
        
        cmd.parseArgs("--api-keys-file", keysFile.toString());
        
        com.aiproxyoauth.config.ServerConfig config = app.buildServerConfig();
        assertEquals("sk-admin-123", config.adminKey());
        assertEquals(1, config.apiKeys().size());
        assertEquals("user1", config.apiKeys().get("sk-user-123"));
        assertFalse(config.apiKeys().containsKey("sk-admin-123"));
    }

    @Test
    void testResolveAvailableModels() throws Exception {
        AIProxyOauth app = new AIProxyOauth();
        com.aiproxyoauth.model.ModelResolver mockResolver = org.mockito.Mockito.mock(com.aiproxyoauth.model.ModelResolver.class);
        
        // Success case
        org.mockito.Mockito.when(mockResolver.resolveModels()).thenReturn(java.util.List.of("gpt-5"));
        java.util.List<String> models = app.resolveAvailableModels(mockResolver);
        assertEquals(java.util.List.of("gpt-5"), models);
        
        // Exception case — suppresses expected "Warning: Could not discover models" stderr output
        org.mockito.Mockito.when(mockResolver.resolveModels()).thenThrow(new RuntimeException("fail"));
        PrintStream saved = System.err;
        System.setErr(NULL_STREAM);
        try {
            java.util.List<String> emptyModels = app.resolveAvailableModels(mockResolver);
            assertTrue(emptyModels.isEmpty());
        } finally {
            System.setErr(saved);
        }
    }

    @Test
    void testPrintStartupBanner() {
        AIProxyOauth app = new AIProxyOauth();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));
        
        com.aiproxyoauth.config.ServerConfig config = new com.aiproxyoauth.config.ServerConfig(
                "127.0.0.1", 10531, null, null, "http://base", null, null, null, "", false, java.util.Map.of("k1", "u1"), "adm"
        );
        
        app.printStartupBanner(config, java.util.List.of("gpt-4"));
        
        String output = sw.toString();
        assertTrue(output.contains("Endpoint: http://127.0.0.1:10531/v1"));
        assertTrue(output.contains("Models:   gpt-4"));
        assertTrue(output.contains("Keys:     1 key(s) configured (u1)"));
        assertTrue(output.contains("Admin:    key configured"));
    }

    @Test
    void testParseApiKeyMap(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        AIProxyOauth app = new AIProxyOauth();
        CommandLine cmd = new CommandLine(app);
        
        // Create a temp keys file
        java.nio.file.Path keysFile = tempDir.resolve("keys.txt");
        java.nio.file.Files.writeString(keysFile, "# Comment line\nfilekey1\napp2:filekey2\n");
        
        cmd.parseArgs("--api-key", "inlinekey1,app1:inlinekey2", "--api-keys-file", keysFile.toString());
        
        java.util.Map<String, String> keyMap = app.parseApiKeyMap();
        
        assertEquals(4, keyMap.size());
        assertEquals("inlinekey1", keyMap.get("inlinekey1"));
        assertEquals("app1", keyMap.get("inlinekey2"));
        assertEquals("filekey1", keyMap.get("filekey1"));
        assertEquals("app2", keyMap.get("filekey2"));
    }

    @Test
    void testCheckAuthFileExists_Failure() {
        AIProxyOauth app = new AIProxyOauth();
        com.aiproxyoauth.config.ServerConfig config = new com.aiproxyoauth.config.ServerConfig(
                "127.0.0.1", 10531, null, null, "http://base", null, null, "/non/existent/path", "", false, java.util.Map.of(), null
        );
        // Suppresses expected "No auth file was found" stderr output
        PrintStream saved = System.err;
        System.setErr(NULL_STREAM);
        try {
            assertFalse(app.checkAuthFileExists(config));
        } finally {
            System.setErr(saved);
        }
    }
}
