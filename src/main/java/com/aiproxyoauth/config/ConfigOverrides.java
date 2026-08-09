package com.aiproxyoauth.config;

import java.util.List;

/** Nullable command-line values. Null means that a lower-precedence layer may supply the value. */
public final class ConfigOverrides {
    public String host;
    public Integer port;
    public String provider;
    public String defaultProvider;
    public String startupCheck;
    public Boolean verbose;
    public String clientKeysFile;
    public String adminClientKeyFile;
    public List<String> corsOrigins;
    public Boolean allowAnyCors;
    public Boolean logRequests;
    public String requestLogDir;
    public String codexModels;
    public String codexVersion;
    public String codexBaseUrl;
    public String codexOauthFile;
    public String codexOauthClientId;
    public String codexOauthTokenUrl;
    public Boolean codexStore;
    public Boolean codexForwardPromptCacheHeaders;
    public String codexInstructionsMode;
    public String codexInstructionsFile;
    public String codexInstructionsCacheDir;
    public String anthropicModels;
    public String anthropicBaseUrl;
    public String anthropicOauthFile;
    public String anthropicTokenUrl;
}
