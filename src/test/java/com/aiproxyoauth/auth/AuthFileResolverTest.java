package com.aiproxyoauth.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthFileResolverTest {

    @Test
    void resolveCandidates_withExplicitPath() {
        String path = "/custom/path/auth.json";
        List<String> candidates = AuthFileResolver.resolveCandidates(path);
        assertEquals(1, candidates.size());
        assertEquals(path, candidates.getFirst());
    }

    @Test
    void resolveCandidates_defaultBehavior() {
        List<String> candidates = AuthFileResolver.resolveCandidates(null);
        assertFalse(candidates.isEmpty());
        // Should contain at least the user home candidates
        String userHome = System.getProperty("user.home");
        assertTrue(candidates.contains(Path.of(userHome, ".chatgpt-local", "auth.json").toString()));
        assertTrue(candidates.contains(Path.of(userHome, ".codex", "auth.json").toString()));
    }

    @Test
    void resolveWritePath_withExplicitPath() {
        String path = "/custom/path/auth.json";
        assertEquals(path, AuthFileResolver.resolveWritePath(path));
    }

    @Test
    void resolveWritePath_defaultBehavior() {
        String path = AuthFileResolver.resolveWritePath(null);
        assertNotNull(path);
        assertTrue(path.contains("auth.json"));
    }
}
