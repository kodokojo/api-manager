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
            emitter.data(data);
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        this.emitter.close();
        this.emitter = null;
    }
}
