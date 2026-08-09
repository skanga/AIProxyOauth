package com.aiproxyoauth.provider.stream;

import java.util.List;

/**
 * Incrementally decodes one upstream response. Implementations own decoder state but never own
 * or close the transport stream.
 */
public interface CompletionStreamDecoder {

    List<CompletionEvent> feed(byte[] bytes);

    List<CompletionEvent> end();
}
