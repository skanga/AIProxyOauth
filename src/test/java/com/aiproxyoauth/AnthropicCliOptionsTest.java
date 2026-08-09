package com.aiproxyoauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicCliOptionsTest {
    @TempDir
    Path temporary;

    @Test
    void helpDocumentsAnthropicCredentialCommands() {
        StringWriter output = new StringWriter();
        CommandLine command = AIProxyOauth.commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("auth", "anthropic", "login", "--help"));

        assertTrue(output.toString().contains("login"));
        assertTrue(output.toString().contains("--anthropic-oauth-file"));
        assertTrue(output.toString().contains("--allow-stdin-oauth-code"));
    }

    @Test
    void oldLoginAndLogoutFlagsAreRejected() {
        StringWriter errors = new StringWriter();
        CommandLine command = AIProxyOauth.commandLine();
        command.setErr(new PrintWriter(errors));

        assertEquals(2, command.execute("--anthropic-login"));
        assertTrue(errors.toString().contains("auth anthropic login"));
    }

    @Test
    void logoutYesDeletesOnlyExplicitAnthropicCredential() throws Exception {
        Path credential = temporary.resolve("anthropic.json");
        Path unrelated = temporary.resolve("codex-auth.json");
        Files.writeString(credential, "{}");
        Files.writeString(unrelated, "{}");
        CommandLine command = AIProxyOauth.commandLine();
        command.setOut(new PrintWriter(new StringWriter()));
        command.setErr(new PrintWriter(new StringWriter()));

        assertEquals(
                0,
                command.execute(
                        "auth", "anthropic", "logout",
                        "--anthropic-oauth-file",
                        credential.toString(),
                        "--yes"
                )
        );

        assertFalse(Files.exists(credential));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void oauthCodeCannotBeSuppliedAsPositionalArgument() {
        CommandLine command = AIProxyOauth.commandLine();
        command.setOut(new PrintWriter(new StringWriter()));
        command.setErr(new PrintWriter(new StringWriter()));

        assertEquals(
                2,
                command.execute(
                        "auth", "anthropic", "login",
                        "secret-code",
                        "--anthropic-oauth-file",
                        temporary.resolve("anthropic.json").toString()
                )
        );
    }
}
