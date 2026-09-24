package com.aiproxyoauth.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/** Classifies only transport failures, after credentials and request validation have succeeded. */
public final class InferenceTransport {
    private InferenceTransport() {}

    public static HttpResponse<InputStream> send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            if (error instanceof HttpTimeoutException || error instanceof InterruptedIOException
                    || Thread.currentThread().isInterrupted()) throw error;
            ConnectException failure = new ConnectException("Upstream connection failed");
            failure.initCause(error);
            throw failure;
        }
    }
}
