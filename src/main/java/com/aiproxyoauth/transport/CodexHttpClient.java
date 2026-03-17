package com.aiproxyoauth.transport;

import com.aiproxyoauth.auth.AuthManager;
import com.aiproxyoauth.config.ServerConfig;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Executors;

public class CodexHttpClient {

    private final HttpClient httpClient;
    private final AuthManager authManager;
    private final String baseUrl;

    public CodexHttpClient(ServerConfig config, AuthManager authManager) {
        this(config, HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), authManager);
    }

    public CodexHttpClient(ServerConfig config, HttpClient httpClient, AuthManager authManager) {
        this.authManager = authManager;
        this.baseUrl = config.baseUrl();
        this.httpClient = httpClient;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public HttpResponse<InputStream> request(String path, String method, String body,
                                              Map<String, String> extraHeaders) throws Exception {
        return httpClient.send(buildRequest(path, method, body, extraHeaders),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    public HttpResponse<String> requestString(String path, String method, String body,
                                               Map<String, String> extraHeaders) throws Exception {
        return httpClient.send(buildRequest(path, method, body, extraHeaders),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest buildRequest(String path, String method, String body,
                                     Map<String, String> extraHeaders) throws Exception {
        String targetUrl = UrlResolver.resolveTargetUrl(path, baseUrl);
        Map<String, String> authHeaders = authManager.getAuthHeaders();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl));

        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        if (body != null && !body.isEmpty()) {
            builder.method(method != null ? method : "POST",
                    HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method != null ? method : "GET",
                    HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }
}
