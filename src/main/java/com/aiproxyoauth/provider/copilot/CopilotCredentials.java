package com.aiproxyoauth.provider.copilot;

import com.aiproxyoauth.config.EffectiveConfig;
import java.io.IOException;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;

public final class CopilotCredentials {
    private static final int LIMIT = 64 * 1024;
    private final EffectiveConfig.Copilot config;
    public CopilotCredentials(EffectiveConfig.Copilot config) { this.config = Objects.requireNonNull(config); }

    public boolean available() {
        try { token(); return true; } catch (IOException error) { return false; }
    }
    public String source() {
        return config.tokenFile() != null ? config.tokenFile().toString()
                : config.environmentToken() != null ? "AIPROXY_COPILOT_TOKEN" : config.oauthFile().toString();
    }
    public synchronized String token() throws IOException {
        if (config.tokenFile() != null) return externalToken(config.tokenFile());
        if (config.environmentToken() != null) return validate(config.environmentToken());
        JsonNode root = readJson(read(config.oauthFile()));
        requireManaged(root);
        return managedToken(root);
    }
    private static String managedToken(JsonNode root) throws IOException {
        if (root.hasNonNull("expires_at")) {
            try {
                if (!Instant.parse(root.path("expires_at").asText()).isAfter(Instant.now().plusSeconds(30))) {
                    throw new IOException("Copilot login expired; run auth copilot login again");
                }
            } catch (java.time.format.DateTimeParseException error) { throw new IOException("Invalid Copilot expiration"); }
        }
        return validate(root.path("access_token").asText());
    }
    private String externalToken(Path path) throws IOException {
        String text = read(path).strip();
        if (!text.startsWith("{") && !text.startsWith("//") && !text.startsWith("/*")) return validate(text);
        JsonNode root = readJson(text);
        if (!root.has("lastLoggedInUser")) {
            requireManaged(root);
            return managedToken(root);
        }
        JsonNode account = root.path("lastLoggedInUser");
        String host = account.path("host").asText();
        if (!host.equals("https://" + config.githubHost())) throw new IOException("Copilot credential host mismatch");
        return validate(root.path("authTokens").path(host + ":" + account.path("login").asText()).path("token").asText());
    }
    private void requireManaged(JsonNode root) throws IOException {
        if (root.path("version").asInt() != 1 || !root.path("provider").asText().equals("copilot")
                || !root.path("github_host").asText().equals(config.githubHost())) {
            throw new IOException("Invalid or mismatched proxy-managed Copilot credential file");
        }
    }
    private static String read(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) throw new IOException("Copilot credential file is too large");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IOException("Could not read Copilot credential file"); }
    }
    private static JsonNode readJson(String text) throws IOException {
        try {
            JsonNode root = JsonMapper.builder().enable(JsonReadFeature.ALLOW_JAVA_COMMENTS).build().readTree(text);
            if (root == null || !root.isObject()) throw new IOException();
            return root;
        } catch (IOException | RuntimeException error) { throw new IOException("Invalid Copilot credential JSON"); }
    }
    private static String validate(String token) throws IOException {
        if (token == null || token.isBlank() || token.length() > 16384 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException("Missing or invalid Copilot token");
        }
        return token;
    }
    public synchronized void save(JsonNode response) throws IOException {
        String token = validate(response.path("access_token").asText());
        Path path = config.oauthFile().toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) requireManaged(readJson(read(path)));
        var root = Json.MAPPER.createObjectNode();
        root.put("version", 1).put("provider", "copilot").put("github_host", config.githubHost())
                .put("access_token", token).put("updated_at", Instant.now().toString());
        if (response.has("expires_in")) {
            long seconds = response.path("expires_in").asLong(-1);
            if (seconds <= 0 || seconds > 366L * 24 * 3600) throw new IOException("Invalid Copilot token lifetime");
            root.put("expires_at", Instant.now().plusSeconds(seconds).toString());
        }
        Path temporary = Files.createTempFile(path.getParent(), "copilot-auth-", ".tmp");
        try {
            protect(temporary);
            Files.write(temporary, Json.MAPPER.writeValueAsBytes(root));
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            protect(path);
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized void logout() throws IOException {
        Path path = config.oauthFile();
        if (Files.exists(path)) { requireManaged(readJson(read(path))); Files.delete(path); }
    }
    private static void protect(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
