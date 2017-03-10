package io.kodokojo.api.endpoint.sse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.kodokojo.api.endpoint.BasicAuthenticator;
import io.kodokojo.commons.event.Event;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.model.ProjectConfiguration;
import io.kodokojo.commons.model.User;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import io.kodokojo.commons.service.repository.UserFetcher;
import javaslang.control.Try;
import org.apache.commons.collections4.IteratorUtils;
import org.eclipse.jetty.servlets.EventSource;
import org.eclipse.jetty.servlets.EventSourceServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class SseServlet extends EventSourceServlet implements EventBus.EventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SseServlet.class);

    private static Set<String> EVENT_TYPE_WHITE_LIST;

    static {
        EVENT_TYPE_WHITE_LIST = new HashSet<>();
        EVENT_TYPE_WHITE_LIST.add(Event.BRICK_STATE_UPDATE);
        EVENT_TYPE_WHITE_LIST.add(Event.PROJECTCONFIG_STARTED);
    }

    private final Map<String, UserSession> caches;

    private final ProjectFetcher projectFetcher;

    private final UserFetcher userFetcher;

    private final Object monitor = new Object();

    @Inject
    public SseServlet(ProjectFetcher projectFetcher, UserFetcher userFetcher) {
        requireNonNull(projectFetcher, "projectFetcher must be defined.");
        requireNonNull(userFetcher, "userFetcher must be defined.");
        this.projectFetcher = projectFetcher;
        this.userFetcher = userFetcher;
        this.caches = new HashMap<>();
    }

    @Override
    protected EventSource newEventSource(HttpServletRequest request) {
        requireNonNull(request, "request must be defined.");
        LOGGER.debug("Client ip '{}' try to create an SSE connection.", request.getRemoteAddr());
        String authenticationHeaderValue = request.getHeader(BasicAuthenticator.AUTHORIZATION_HEADER_NAME);
        String[] userAndPassword = BasicAuthenticator.explodeUserAndPassword(authenticationHeaderValue);
        if (userAndPassword != null && userAndPassword.length == 2) {
            String username = userAndPassword[0];
            User user = userFetcher.getUserByUsername(username);
            if (user != null && user.getPassword().equals(userAndPassword[1])) {

                UserSession userSession = addUserSession(user, request);
                return userSession.sseEventOutput;

            } else {
                LOGGER.debug("A user fail to be authenticated as user {}.", username);
            }
        }
        LOGGER.debug("Client ip '{}' don't provide any authentication.", request.getRemoteAddr());
        return null;
    }

    @Override
    public Try<Boolean> receive(Event event) {
        requireNonNull(event, "event must be defined.");
        String data = Event.convertToJson(event);
        if (EVENT_TYPE_WHITE_LIST.contains(event.getEventType())) {
            Map<String, String> headers = event.getCustom();
            String projectConfigurationIdentifier = headers.get(Event.PROJECTCONFIGURATION_ID_CUSTOM_HEADER);
            if (isNotBlank(projectConfigurationIdentifier)) {
                ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(projectConfigurationIdentifier);
                if (projectConfiguration != null) {
                    Set<UserSession> usersToNotify = computeListOfUserFromProjectConfiguration(projectConfiguration);
                    usersToNotify.forEach(u -> {
                        try {
                            u.sseEventOutput.send(data);
                            if (LOGGER.isTraceEnabled()) {
                                LOGGER.trace("Following event sent to user '{}':\n{}", u.user.getUsername(), Event.convertToPrettyJson(event));
                            }
                        } catch (IOException e) {
                            LOGGER.error("Unable to send following event to user '{}': \n{}", u.user.getUsername(), Event.convertToPrettyJson(event), e);
                        }
                    });
                    return Try.success(true);
                }
            }
        }
        /*
        caches.values().stream().forEach(u -> {
            try {
                u.sseEventOutput.send(data);
            } catch (IOException e) {
                LOGGER.error("Unable to send following event to user '{}': \n{}", u.user.getUsername(), Event.convertToPrettyJson(event), e);
            }
        });
        */
        return Try.success(false);
    }

    private Set<UserSession> computeListOfUserFromProjectConfiguration(ProjectConfiguration projectConfiguration) {
        Map<String, UserSession> tmp = null;
        synchronized (monitor) {
            tmp = new HashMap<>(caches);
        }
        final Map<String, UserSession> copy = tmp;
        Set<User> users = new HashSet<>();
        users.addAll(IteratorUtils.toList(projectConfiguration.getUsers()));
        users.addAll(IteratorUtils.toList(projectConfiguration.getTeamLeaders()));
        return users.stream()
                .map(u -> copy.get(u.getIdentifier()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private UserSession addUserSession(User user, HttpServletRequest request) {
        UserSession userSession = new UserSession(user, new SSEEventOutput());
        synchronized (monitor) {
            caches.put(user.getIdentifier(), userSession);
        }
        return userSession;
    }

    private class UserSession {

        private final User user;

        private final SSEEventOutput sseEventOutput;

        private boolean open;

        public UserSession(User user, SSEEventOutput sseEventOutput) {
            requireNonNull(user, "user must be defined.");
            requireNonNull(sseEventOutput, "sseEventOutput must be defined.");
            this.user = user;
            this.sseEventOutput = sseEventOutput;
            this.open = true;
        }

        public void send(String data) throws IOException {
            if (isBlank(data)) {
                throw new IllegalArgumentException("data must be defined.");
            }
            sseEventOutput.send(data);
        }

        public void onOpen(EventSource.Emitter emitter) throws IOException {
            requireNonNull(emitter, "emitter must be defined.");
            sseEventOutput.onOpen(emitter);
        }

        public void onClose() {
            sseEventOutput.onClose();
        }
    }

}
