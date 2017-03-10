package io.kodokojo.api.endpoint.sse;

import org.eclipse.jetty.servlets.EventSource;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class SSEEventOutputTest {

    @Test
    public void acceptance_test() {
        EventSource.Emitter emitter = Mockito.mock(EventSource.Emitter.class);


        SSEEventOutput sseEventOutput = new SSEEventOutput();
        boolean sent = false;
        try {
            sent = sseEventOutput.send("data");
            assertThat(sent).isFalse();
        } catch (IOException e) {
            fail(e.getMessage());
        }

        try {
            sseEventOutput.onOpen(emitter);
            sent = sseEventOutput.send("data");
            assertThat(sent).isTrue();
        } catch (IOException e) {
            fail(e.getMessage());
        }

        try {
            Mockito.verify(emitter, Mockito.atLeastOnce()).data("data");
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

}