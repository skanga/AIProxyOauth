package com.aiproxyoauth;

import com.aiproxyoauth.config.ServerConfig;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class GenerateKeyTest {

    @Test void generatedKeyStartsWithPrefix() {
        String key = captureOutput("key", "generate");
        assertTrue(key.startsWith(ServerConfig.KEY_PREFIX),
                "Expected key to start with '" + ServerConfig.KEY_PREFIX + "' but got: " + key);
    }

    @Test void generatedKeyHasCorrectLength() {
        String key = captureOutput("key", "generate");
        // "sk-proxy-" (9 chars) + 32 lowercase hex chars = 41
        assertEquals(41, key.length(), "Key should be 41 chars: " + key);
    }

    @Test void generatedKeyHexSuffixIsLowercase() {
        String key = captureOutput("key", "generate");
        String suffix = key.substring(ServerConfig.KEY_PREFIX.length());
        assertTrue(suffix.matches("[0-9a-f]+"), "Hex suffix should be lowercase hex: " + suffix);
    }

    @Test void generateKeyExitsWithCode0() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exitCode = new CommandLine(new AIProxyOauth()).execute("key", "generate");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(old);
        }
    }

    @Test void consecutiveGeneratedKeysAreDifferent() {
        String key1 = captureOutput("key", "generate");
        String key2 = captureOutput("key", "generate");
        assertNotEquals(key1, key2, "Each generated key should be unique");
    }

    @Test void namedKey_outputHasNamePrefix() {
        String output = captureOutput("key", "generate", "cursor");
        assertTrue(output.startsWith("cursor:"),
                "Named output should start with 'cursor:' but got: " + output);
    }

    @Test void namedKey_keyPartStartsWithPrefix() {
        String output = captureOutput("key", "generate", "cursor");
        String key = output.substring("cursor:".length());
        assertTrue(key.startsWith(ServerConfig.KEY_PREFIX),
                "Key part should start with prefix but got: " + key);
    }

    @Test void namedKey_totalLengthCorrect() {
        String output = captureOutput("key", "generate", "cursor");
        // "cursor:" (7) + "sk-proxy-" (9) + 32 hex = 48
        assertEquals(48, output.length(), "Named output should be 48 chars: " + output);
    }

    @Test void namedKey_canBeDirectlyParsedAsKeyEntry() {
        String output = captureOutput("key", "generate", "myapp");
        // Verify it splits correctly into name and key
        int colon = output.indexOf(':');
        assertTrue(colon > 0);
        assertEquals("myapp", output.substring(0, colon));
        assertTrue(output.substring(colon + 1).startsWith(ServerConfig.KEY_PREFIX));
    }

    private static String captureOutput(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out));
        try {
            new CommandLine(new AIProxyOauth()).execute(args);
        } finally {
            System.setOut(old);
        }
        return out.toString().trim();
    }
}
