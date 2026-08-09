package com.aiproxyoauth.provider.anthropic;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

final class IncrementalSseFramer {
    static final int DEFAULT_MAX_EVENT_BYTES = 4 * 1024 * 1024;

    record Event(String name, String data) {
    }

    static final class FrameLimitException extends RuntimeException {
        private FrameLimitException() {
            super("SSE frame exceeded the configured byte limit");
        }
    }

    static final class FrameFormatException extends RuntimeException {
        private FrameFormatException(Throwable cause) {
            super("SSE frame contained invalid UTF-8", cause);
        }
    }

    private final int maximumBytes;
    private byte[] buffer = new byte[8192];
    private int size;
    private int readPosition;
    private String eventName = "";
    private final StringBuilder data = new StringBuilder();
    private int accumulatedDataBytes;

    IncrementalSseFramer(int maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    void feed(byte[] bytes, Consumer<Event> consumer) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(consumer, "consumer");
        int unread = size - readPosition;
        if ((long) unread + bytes.length > maximumBytes) {
            throw new FrameLimitException();
        }
        compact();
        ensureCapacity(size + bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;

        int newline;
        while ((newline = findNewline()) >= 0) {
            int lineEnd = newline > readPosition && buffer[newline - 1] == '\r'
                    ? newline - 1
                    : newline;
            String line = decodeLine(readPosition, lineEnd);
            readPosition = newline + 1;
            consumeLine(line, consumer);
        }
        compact();
    }

    boolean hasPendingData() {
        return size > 0 || !eventName.isEmpty() || !data.isEmpty();
    }

    private void consumeLine(String line, Consumer<Event> consumer) {
        if (line.isEmpty()) {
            if (!eventName.isEmpty() || !data.isEmpty()) {
                consumer.accept(new Event(eventName, data.toString()));
            }
            eventName = "";
            data.setLength(0);
            accumulatedDataBytes = 0;
            return;
        }
        if (line.startsWith(":")) {
            return;
        }
        if (line.startsWith("event:")) {
            eventName = value(line, 6);
        } else if (line.startsWith("data:")) {
            String value = value(line, 5);
            int addition = value.getBytes(StandardCharsets.UTF_8).length
                    + (data.isEmpty() ? 0 : 1);
            if ((long) accumulatedDataBytes + addition > maximumBytes) {
                throw new FrameLimitException();
            }
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(value);
            accumulatedDataBytes += addition;
        }
    }

    private int findNewline() {
        for (int index = readPosition; index < size; index++) {
            if (buffer[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private String decodeLine(int start, int end) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer, start, end - start))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new FrameFormatException(error);
        }
    }

    private void compact() {
        if (readPosition == 0) {
            return;
        }
        int remaining = size - readPosition;
        System.arraycopy(buffer, readPosition, buffer, 0, remaining);
        size = remaining;
        readPosition = 0;
    }

    private void ensureCapacity(int required) {
        if (required <= buffer.length) {
            return;
        }
        int doubled = Math.multiplyExact(buffer.length, 2);
        buffer = Arrays.copyOf(buffer, Math.min(maximumBytes, Math.max(required, doubled)));
    }

    private static String value(String line, int offset) {
        int index = offset;
        if (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return line.substring(index);
    }
}
