package com.aiproxyoauth;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class CliV2Test {
    @Test
    void rootHelpDescribesProtocolsAndSubcommands() {
        StringWriter output = new StringWriter();
        CommandLine command = AIProxyOauth.commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("--help"));
        String help = output.toString();
        assertTrue(help.contains("OAuth proxy exposing OpenAI-compatible and Anthropic-compatible APIs"));
        assertTrue(help.contains("serve"));
        assertTrue(help.contains("auth"));
        assertTrue(help.contains("key"));
        assertTrue(help.contains("config"));
        assertTrue(help.contains("doctor"));
        assertFalse(help.contains("--api-key"));
    }

    @Test
    void serveHelpUsesExplicitProviderAndClientAuthNames() {
        StringWriter output = new StringWriter();
        CommandLine command = AIProxyOauth.commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("serve", "--help"));
        String help = output.toString();
        assertTrue(help.contains("--provider"));
        assertTrue(help.contains("--client-keys-file"));
        assertTrue(help.contains("--codex-models"));
        assertTrue(help.contains("--codex-instructions-file"));
        assertTrue(help.contains("--startup-check"));
        assertFalse(help.contains("--models"));
        assertFalse(help.contains("--providers"));
    }

    @Test
    void removedFlagFailsWithReplacement() {
        StringWriter errors = new StringWriter();
        CommandLine command = AIProxyOauth.commandLine();
        command.setErr(new PrintWriter(errors));

        assertEquals(2, command.execute("serve", "--models", "gpt-test"));
        assertTrue(errors.toString().contains("--codex-models"));
    }

    @Test
    void keyGenerateIsACommand() {
        StringWriter output = new StringWriter();
        CommandLine command = AIProxyOauth.commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("key", "generate", "myapp"));
        assertTrue(output.toString().matches("(?s)myapp:sk-proxy-[0-9a-f]{32}\\R"));
    }

    @Test
    void configShowAlwaysRedactsEnvironmentSecrets() {
        StringWriter output = new StringWriter();
        AIProxyOauth root = new AIProxyOauth(() -> java.util.Map.of(
                "AIPROXY_CLIENT_KEYS", "top-secret",
                "AIPROXY_ADMIN_CLIENT_KEY", "admin-secret"));
        CommandLine command = AIProxyOauth.commandLine(root);
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("config", "show"));
        assertTrue(output.toString().contains("<redacted>"));
        assertFalse(output.toString().contains("top-secret"));
        assertFalse(output.toString().contains("admin-secret"));
    }
}
