package io.kodokojo.api.endpoint.sse;

import org.eclipse.jetty.servlets.EventSource;

import java.io.IOException;

import static org.apache.commons.lang.StringUtils.isBlank;

public class SSEEventOutput implements EventSource {

    private Emitter emitter;

    @Override
    public void onOpen(Emitter emitter) throws IOException {
        this.emitter = emitter;
    }

    public boolean send(String data) throws IOException {
        if (isBlank(data)) {
            throw new IllegalArgumentException("data must be defined.");
        }
        if (emitter != null) {
            try {
                emitter.data(data);
            } catch (IllegalStateException e) {
                if (SSE_EXCEPTION_MESSAGE.equals(e.getMessage())) {
                    throw new IOException(e);
                }
                throw e;
            }
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        this.emitter.close();
        this.emitter = null;
    }

    private static final String SSE_EXCEPTION_MESSAGE = "AsyncContext completed and/or Request lifecycle recycled";
}
