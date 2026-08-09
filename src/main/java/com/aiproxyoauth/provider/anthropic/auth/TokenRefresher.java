package com.aiproxyoauth.provider.anthropic.auth;

import java.io.IOException;

@FunctionalInterface
interface TokenRefresher {
    OAuthTokenSet refresh(String refreshToken) throws IOException;
}
