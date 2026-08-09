package com.aiproxyoauth.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.OptionalLong;

public final class BoundedBodyReader {
    private BoundedBodyReader() {
    }

    public static byte[] read(HttpResponse<InputStream> response, int maximumBytes)
            throws IOException {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
        try (InputStream body = response.body()) {
            if (contentLength.isPresent() && contentLength.getAsLong() > maximumBytes) {
                throw new BodyTooLargeException(maximumBytes);
            }
            byte[] bytes = body.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new BodyTooLargeException(maximumBytes);
            }
            return bytes;
        }
    }

    public static final class BodyTooLargeException extends IOException {
        private final int maximumBytes;

        public BodyTooLargeException(int maximumBytes) {
            super("Response exceeded the configured byte limit");
            this.maximumBytes = maximumBytes;
        }

        public int maximumBytes() {
            return maximumBytes;
        }
    }
}
