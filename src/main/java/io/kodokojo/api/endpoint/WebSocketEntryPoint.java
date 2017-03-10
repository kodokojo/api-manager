/**
 * Kodo Kojo - API frontend which dispatch REST event to Http services or publish event on EvetnBus.
 * Copyright © 2017 Kodo Kojo (infos@kodokojo.io)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.kodokojo.api.endpoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.kodokojo.api.Launcher;
import io.kodokojo.commons.dto.BrickEventStateWebSocketMessage;
import io.kodokojo.commons.dto.WebSocketMessageGsonAdapter;
import io.kodokojo.commons.event.Event;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.event.GsonEventSerializer;
import io.kodokojo.commons.model.ProjectConfiguration;
import io.kodokojo.commons.model.User;
import io.kodokojo.commons.service.BrickUrlFactory;
import io.kodokojo.commons.service.actor.message.BrickStateEvent;
import io.kodokojo.commons.service.repository.OrganisationFetcher;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import io.kodokojo.commons.service.repository.UserFetcher;
import javaslang.control.Try;
import org.apache.commons.collections.IteratorUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

//  WebSocket close event code https://developer.mozilla.org/fr/docs/Web/API/CloseEvent
@WebSocket
@Deprecated
public class WebSocketEntryPoint implements EventBus.EventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketEntryPoint.class);

    public static final long USER_VALIDATION_TIMEOUT = 10000;

    private final Map<Session, Long> sessions;

    private final Map<String, UserSession> userConnectedSession;

    private final UserFetcher userRepository;

    private final ProjectFetcher projectFetcher;

    private final OrganisationFetcher organisationFetcher;

    private final EventBus eventBus;

    private final BrickUrlFactory brickUrlFactory;

    private final ThreadLocal<Gson> localGson = new ThreadLocal<Gson>() {
        @Override
        protected Gson initialValue() {
            GsonBuilder builder = new GsonBuilder();
            builder.registerTypeAdapter(BrickEventStateWebSocketMessage.class, new WebSocketMessageGsonAdapter());
            builder.registerTypeAdapter(Event.class, new GsonEventSerializer());
            return builder.create();
        }
    };

    //  WebSocket is built by Spark but we are not able to get the instance :/ .
    //  See : https://github.com/perwendel/spark/pull/383
    public WebSocketEntryPoint() {
        super();
        sessions = new ConcurrentHashMap<>();
        userConnectedSession = new ConcurrentHashMap<>();
        userRepository = null;
        projectFetcher = null;
        organisationFetcher = null;
        brickUrlFactory = null;
        eventBus = null;
        LOGGER.info("WebSocketEntryPoint available");
        eventBus.addEventListener(this);
    }

    @OnWebSocketConnect
    public void connected(Session session) {
        LOGGER.info("Create a new session to {}.", session.getRemoteAddress().getHostString());
        sessions.put(session, System.currentTimeMillis());
    }

    @OnWebSocketMessage
    public void message(Session session, String message) throws IOException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Receive following message: {}", message);
        }
        UserSession userSession = sessionIsValidated(session);
        if (userSession == null) {
            Long connectDate = sessions.get(session);
            long delta = (connectDate + USER_VALIDATION_TIMEOUT) - System.currentTimeMillis();
            if (delta < 0) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Session of user wait for to many times....");
                }
                session.close(); // To late to connect.
            } else {
                Gson gson = localGson.get();
                BrickEventStateWebSocketMessage brickEventStateWebSocketMessage = gson.fromJson(message, BrickEventStateWebSocketMessage.class);
                JsonObject data = null;
                if ("user".equals(brickEventStateWebSocketMessage.getEntity())
                        && "authentication".equals(brickEventStateWebSocketMessage.getAction())
                        && brickEventStateWebSocketMessage.getData().has("authorization")) {
                    data = brickEventStateWebSocketMessage.getData();
                    String encodedAutorization = data.getAsJsonPrimitive("authorization").getAsString();
                    if (encodedAutorization.startsWith("Basic ")) {
                        String encodedCredentials = encodedAutorization.substring("Basic ".length());
                        String decoded = new String(Base64.getDecoder().decode(encodedCredentials));
                        String[] credentials = decoded.split(":");
                        if (credentials.length != 2) {
                            sessions.remove(session);
                            session.close(1008, "Authorization value in data mal formatted");
                        } else {
                            User user = userRepository.getUserByUsername(credentials[0]);
                            if (user == null) {
                                sessions.remove(session);
                                session.close(4401, "Invalid credentials for user '" + credentials[0] + "'.");
                            } else {
                                if (user.getPassword().equals(credentials[1])) {
                                    userConnectedSession.put(user.getIdentifier(), new UserSession(session, user));
                                    sessions.remove(session);
                                    JsonObject dataValidate = new JsonObject();
                                    dataValidate.addProperty("message", "success");
                                    dataValidate.addProperty("identifier", user.getIdentifier());
                                    BrickEventStateWebSocketMessage response = new BrickEventStateWebSocketMessage("user", "authentication", dataValidate);
                                    String responseStr = gson.toJson(response);
                                    session.getRemote().sendString(responseStr);
                                    if (LOGGER.isDebugEnabled()) {
                                        LOGGER.debug("Send following message to user {} : {}", user.getUsername(), responseStr);
                                    }
                                } else {
                                    sessions.remove(session);
                                    session.close(4401, "Invalid credentials.");

                                }
                            }
                        }
                    } else {
                        sessions.remove(session);
                        session.close(1008, "Authentication value in data attribute mal formatted : " + data.toString());
                    }
                } else {
                    sessions.remove(session);
                    session.close();
                }
            }
        } else {
            userSession.setLastActivityDate(System.currentTimeMillis());
            LOGGER.debug("Receive following message from user '{}'.", userSession.getUser().getName());
        }
    }

    @OnWebSocketClose
    public void closed(Session session, int statusCode, String reason) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Connection closed for reason '{}' with status code {}.", reason, statusCode);
        }
        LOGGER.info("Connection closed for reason '{}' with status code {}.", reason, statusCode);
        sessions.remove(session);
        String identifier = null;
        Iterator<Map.Entry<String, UserSession>> iterator = userConnectedSession.entrySet().iterator();
        while (iterator.hasNext() && identifier == null) {
            Map.Entry<String, UserSession> sessionEntry = iterator.next();
            if (sessionEntry.getValue().getSession().equals(session)) {
                identifier = sessionEntry.getKey();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Connection closed attach to user {}.", sessionEntry.getValue().getUser().getUsername());
                }
            }
        }
        if (identifier != null) {
            userConnectedSession.remove(identifier);
        }

    }

    private void sendMessageToUser(String message, UserSession userSession) {
        try {
            userSession.getSession().getRemote().sendString(message);
            LOGGER.info("Following message sent to user {} : {}", userSession.getUser().getUsername(), message);
        } catch (IOException e) {
            LOGGER.error("Unable to notify user {}.", userSession.getUser().getUsername());
        }
    }

    private BrickEventStateWebSocketMessage convertToBrickEventStateWebSocketMessage(BrickStateEvent brickStateEvent) {
        JsonObject data = new JsonObject();
        data.addProperty("projectConfiguration", brickStateEvent.getProjectConfigurationIdentifier());
        data.addProperty("brickType", brickStateEvent.getBrickType());
        data.addProperty("brickName", brickStateEvent.getBrickName());
        data.addProperty("state", brickStateEvent.getState().name());
        if (brickStateEvent.getState() == BrickStateEvent.State.RUNNING) {
            ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(brickStateEvent.getProjectConfigurationIdentifier());
            data.addProperty("url", "https://" + brickUrlFactory.forgeUrl(projectConfiguration.getName(), brickStateEvent.getStackName(), brickStateEvent.getBrickType(), brickStateEvent.getBrickName()));
        }
        if (brickStateEvent.getState() == BrickStateEvent.State.ONFAILURE) {
            data.addProperty("message", brickStateEvent.getMessage());
        }

        return new BrickEventStateWebSocketMessage("brick", "updateState", data);
    }

    private UserSession sessionIsValidated(Session session) {
        assert session != null : "session must be defined";
        UserSession res = null;
        Iterator<UserSession> iterator = userConnectedSession.values().iterator();
        while (res == null && iterator.hasNext()) {
            UserSession current = iterator.next();
            if (current.getSession().equals(session)) {
                res = current;
            }
        }
        return res;
    }

    @Override
    public Try<Boolean> receive(Event event) {
        requireNonNull(event, "event must be defined.");
        LOGGER.debug("Receive event:\n{}", event);
        String broadcastFrom = event.getCustom().get(Event.BROADCAST_FROM_CUSTOM_HEADER);
        if (StringUtils.isBlank(broadcastFrom) &&
                !eventBus.getFrom().equals(broadcastFrom) &&
                event.getRequestReplyType() != Event.RequestReplyType.REQUEST
                ) {

            eventBus.broadcastToSameService(event);
            Set<UserSession> userSessions = new HashSet<>();
            if (event.getCustom().containsKey(Event.PROJECTCONFIGURATION_ID_CUSTOM_HEADER)) {
                Set<UserSession> usersToNotifyed = usersToNotifyed(projectFetcher.getProjectConfigurationById(event.getCustom().get(Event.PROJECTCONFIGURATION_ID_CUSTOM_HEADER)));
                userSessions.addAll(usersToNotifyed);
            } else if (event.getCustom().containsKey(Event.ORGANISATION_ID_CUSTOM_HEADER)) {
                String entityId = event.getCustom().get(Event.ORGANISATION_ID_CUSTOM_HEADER);
                List<ProjectConfiguration> projectConfigurations = IteratorUtils.toList(organisationFetcher.getOrganisationById(entityId).getProjectConfigurations());
                Set<UserSession> collect = projectConfigurations.stream().flatMap(p -> usersToNotifyed(p).stream()).collect(Collectors.toSet());
                userSessions.addAll(collect);
            }

            Gson gson = localGson.get();
            String jsonEven = gson.toJson(event);
            userSessions.forEach(u -> sendMessageToUser(jsonEven, u));
        }

        return Try.success(Boolean.TRUE);
    }

    private Set<UserSession> usersToNotifyed(ProjectConfiguration projectConfiguration) {
        assert projectConfiguration != null : "projectConfiguration mus be defined.";

        Function<User, UserSession> userUserSessionMapper = u -> userConnectedSession.get(u.getIdentifier());

        Set<UserSession> res = ((List<User>) IteratorUtils.toList(projectConfiguration.getTeamLeaders())).stream()
                .map(userUserSessionMapper)
                .collect(Collectors.toSet());
        ((List<User>) IteratorUtils.toList(projectConfiguration.getUsers())).stream()
                .map(userUserSessionMapper)
                .forEach(res::add);

        return res;
    }

    private class UserSession {

        private final Session session;

        private final User user;

        private long lastActivityDate;

        public UserSession(Session session, User user) {
            this.session = session;
            this.user = user;
            this.lastActivityDate = System.currentTimeMillis();
        }

        public Session getSession() {
            return session;
        }

        public User getUser() {
            return user;
        }

        public long getLastActivityDate() {
            return lastActivityDate;
        }

        public void setLastActivityDate(long lastActivityDate) {
            if (this.lastActivityDate < lastActivityDate)
                this.lastActivityDate = lastActivityDate;
        }
    }

}
