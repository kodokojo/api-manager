package io.kodokojo.api.endpoint;

import io.kodokojo.api.endpoint.sse.SseServlet;
import io.kodokojo.commons.service.lifecycle.ApplicationLifeCycleListener;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Spark;
import spark.servlet.SparkApplication;
import spark.servlet.SparkFilter;

import javax.inject.Inject;
import javax.servlet.DispatcherType;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

public class JettySupport implements ApplicationLifeCycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(JettySupport.class);

    private final int port;

    private final HttpEndpoint httpEndpoint;

    private final SseServlet sseServlet;
    
    private final Object monitor = new Object();

    private Server server;

    @Inject
    public JettySupport(int port, HttpEndpoint httpEndpoint, SseServlet sseServlet) {
        requireNonNull(httpEndpoint, "httpEndpoint must be defined.");
        requireNonNull(sseServlet, "sseServlet must be defined.");
        this.port = port;
        this.httpEndpoint = httpEndpoint;
        this.sseServlet = sseServlet;
    }

    @Override
    public void start() {
        if (server == null) {
            synchronized (monitor) {
                if (server == null) {
                    doStart();
                }
            }
        }
    }

    private void doStart() {
        CountDownLatch startLatch = new CountDownLatch(1);
        server = createAndConfigureJettyServer();
        LOGGER.info("Starting web server on port {}.", port);
        new Thread(() -> {
            try {
                server.start();
                startLatch.countDown();
                server.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        try {
            startLatch.await(10, TimeUnit.SECONDS);
            LOGGER.info("Web server started on port {}.", port);
        } catch (InterruptedException e) {
            LOGGER.error("Unable to start Jetty server.", e);
            server = null;
        }
    }

    private Server createAndConfigureJettyServer() {

        Server server = new Server(port);

        ServletContextHandler context = new ServletContextHandler();
        ServletHolder sseServletHolder = new ServletHolder(sseServlet);
        sseServletHolder.setAsyncSupported(true);
        context.addServlet(sseServletHolder, HttpEndpoint.BASE_API + "/event");


        FilterHolder sparkFilter = new FilterHolder(new SparkFilter() {
            @Override
            protected SparkApplication[] getApplications(FilterConfig filterConfig) throws ServletException {
                return new SparkApplication[] {httpEndpoint};
            }
        });

        //context.addFilter(sparkFilter, "/api/v1", EnumSet.allOf(DispatcherType.class));
        context.addFilter(sparkFilter, "/*", EnumSet.allOf(DispatcherType.class));

/*
        String webAppDirectory = getClass().getClassLoader().getResource("webapp").toExternalForm();
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setResourceBase(webAppDirectory);

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{resourceHandler,context});
*/
        server.setHandler(context);
        return server;
    }

    @Override
    public void stop() {
        if (server != null) {
            synchronized (monitor) {
                if (server != null) {
                    try {
                        LOGGER.info("Stopping HttpEndpoint.");
                        Spark.stop();

                        server.stop();
                    } catch (Exception e) {
                        LOGGER.error("Unable to stop jetty server.", e);
                    } finally {
                        server = null;
                    }
                }
            }
        }
    }
}

