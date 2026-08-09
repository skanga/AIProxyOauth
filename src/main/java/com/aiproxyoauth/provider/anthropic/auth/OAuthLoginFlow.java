package com.aiproxyoauth.provider.anthropic.auth;

import java.io.IOException;

interface OAuthLoginFlow {
    AnthropicOAuthLogin.Attempt newAttempt();

    OAuthTokenSet exchange(String callback, AnthropicOAuthLogin.Attempt attempt) throws IOException;
}
