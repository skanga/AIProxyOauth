package com.aiproxyoauth.provider.anthropic.auth;

import java.io.IOException;
import java.util.Optional;

interface AnthropicCredentialRepository {
    Optional<AnthropicCredential> load() throws IOException;

    void save(AnthropicCredential credential) throws IOException;
}
