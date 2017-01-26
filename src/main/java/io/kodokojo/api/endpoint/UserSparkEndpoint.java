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
import com.google.gson.JsonParser;
import io.kodokojo.api.service.ReCaptchaService;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.dto.UserCreationDto;
import io.kodokojo.commons.dto.UserDto;
import io.kodokojo.commons.dto.UserProjectConfigIdDto;
import io.kodokojo.commons.dto.UserUpdateDto;
import io.kodokojo.commons.event.Event;
import io.kodokojo.commons.event.EventBuilder;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.event.payload.UserCreationReply;
import io.kodokojo.commons.event.payload.UserCreationRequest;
import io.kodokojo.commons.model.User;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import io.kodokojo.commons.service.repository.UserFetcher;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static spark.Spark.*;

public class UserSparkEndpoint extends AbstractSparkEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserSparkEndpoint.class);

    private final UserFetcher userFetcher;

    private final ProjectFetcher projectFetcher;

    private final ReCaptchaService reCaptchaService;

    @Inject
    public UserSparkEndpoint(UserAuthenticator<SimpleCredential> userAuthenticator, EventBus eventBus, EventBuilderFactory eventBuilderFactory, UserFetcher userFetcher, ProjectFetcher projectFetcher, ReCaptchaService reCaptchaService) {
        super(userAuthenticator, eventBus, eventBuilderFactory);
        requireNonNull(userFetcher, "userFetcher must be defined.");
        requireNonNull(projectFetcher, "projectFetcher must be defined.");
        requireNonNull(reCaptchaService, "reCaptchaService must be defined.");
        this.userFetcher = userFetcher;
        this.projectFetcher = projectFetcher;
        this.reCaptchaService = reCaptchaService;
    }

    @Override
    public void configure() {
        patch(BASE_API + "/user/:id", JSON_CONTENT_TYPE, ((request, response) -> {
            String identifier = request.params(":id");
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Try to update user with id {}", identifier);
            }

            User requester = getRequester(request);
            User user = userFetcher.getUserByIdentifier(identifier);

            if (requester.getIdentifier().equals(user.getIdentifier())) {

                JsonParser parser = new JsonParser();

                Gson gson = new GsonBuilder().create();
                UserUpdateDto userDto = gson.fromJson(request.body(), UserUpdateDto.class);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Trying to upate following user: {}", userDto.toString());
                }

                EventBuilder eventBuilder = eventBuilderFactory.create();

                eventBuilder.setEventType(Event.USER_UPDATE_REQUEST);
                eventBuilder.setJsonPayload(request.body());

                Event reply = eventBus.request(eventBuilder.build(), 5, TimeUnit.SECONDS);
                if (reply == null) {
                    halt(500, "Unable to update User");
                } else {
                    String replyPayload = reply.getPayload();
                    parser = new JsonParser();
                    JsonObject replyRoot = (JsonObject) parser.parse(replyPayload);
                    Boolean success = readBooleanFromJson(replyRoot, "state").orElse(Boolean.FALSE);
                    if (success) {
                        halt(200);
                    } else {
                        halt(500, "Unable to update user " + user.getUsername() + ".");
                    }

                }

            } else {
                halt(403, "Your aren't allow to update this user.");
            }

            return "";
        }), jsonResponseTransformer);

        post(BASE_API + "/user/:id", JSON_CONTENT_TYPE, ((request, response) -> {
            String identifier = request.params(":id");
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Try to create user with id {}", identifier);
            }

            User requester = getRequester(request);
            if (!validateThrowCaptcha(request, requester)) return "";

            JsonParser parser = new JsonParser();
            JsonObject json = (JsonObject) parser.parse(request.body());
            String email = json.getAsJsonPrimitive("email").getAsString();
            String username = email.substring(0, email.lastIndexOf("@"));

            String entityId = "";
            if (requester != null) {
                entityId = requester.getEntityIdentifier();
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Create a new User with a new Entity");
            }

            EventBuilder eventBuilder = eventBuilderFactory.create();
            eventBuilder.setEventType(Event.USER_CREATION_REQUEST);
            if (requester != null) {
                eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
            }
            eventBuilder.setPayload(new UserCreationRequest(identifier, email, username, entityId));

            Event reply = eventBus.request(eventBuilder.build(), 30, TimeUnit.SECONDS);

            if (reply == null) {
                halt(500, "Unable to create user " + username + " in less than 30 seconds.");
            } else {
                UserCreationReply userCreationReply = reply.getPayload(UserCreationReply.class);
                if (userCreationReply.isUserInWaitingList()) {
                    LOGGER.info("Set user '{}' in waiting list to creation.", username);
                    response.status(202);
                    return "";
                } else if (!userCreationReply.isUsernameEligible()) {
                    halt(428, "Identifier or username are not valid.");
                    return "";
                } else if (StringUtils.isNotBlank(userCreationReply.getUserId()) &&
                        userCreationReply.getPrivateKey() != null) {
                    User user = userFetcher.getUserByIdentifier(identifier);
                    response.status(201);
                    response.header("Location", "/user/" + user.getIdentifier());
                    return new UserCreationDto(user, userCreationReply.getPrivateKey());
                }
            }

            halt(500, "An unexpected behaviour happened while trying to create user " + username + ".");
            return "";
        }), jsonResponseTransformer);

        post(BASE_API + "/user", JSON_CONTENT_TYPE, (request, response) -> {

            LOGGER.debug("Require a new user Identifier.");
            EventBuilder eventBuilder = eventBuilderFactory.create()
                    .setEventType(Event.USER_IDENTIFIER_CREATION_REQUEST)
                    .setJsonPayload("");
            Event reply = eventBus.request(eventBuilder.build(), 30, TimeUnit.SECONDS);
            if (reply != null && StringUtils.isNotBlank(reply.getPayload())) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("get result :{}", reply.getPayload().substring(1, reply.getPayload().length() - 1));
                }
                return reply.getPayload();
            }
            halt(500, "An unexpected error occur while trying to generate a new user Id.");
            return "";
        });

        get(BASE_API + "/user", JSON_CONTENT_TYPE, (request, response) -> {
            User requester = getRequester(request);
            return getUserDto(requester);
        }, jsonResponseTransformer);

        get(BASE_API + "/user/:id", JSON_CONTENT_TYPE, (request, response) -> {
            User requester = getRequester(request);
            String identifier = request.params(":id");
            User user = userFetcher.getUserByIdentifier(identifier);
            if (user != null) {
                if (user.getEntityIdentifier().equals(requester.getEntityIdentifier())) {
                    if (!user.equals(requester)) {
                        user = new User(user.getIdentifier(), user.getEntityIdentifier(), user.getName(), user.getUsername(), user.getEmail(), "", user.getSshPublicKey());
                    }
                    return getUserDto(user);
                }
                halt(403, "You aren't in same entity.");
                return "";
            }
            halt(404);
            return "";
        }, jsonResponseTransformer);
    }

    private boolean validateThrowCaptcha(Request request, User requester) {
        if (requester == null) {
            if (reCaptchaService.isConfigured()) {
                String captcha = request.headers("g-recaptcha-response");
                if (StringUtils.isBlank(captcha)) {
                    halt(428, "Unable to retrieve a valid user or Captcha.");
                    return false;
                } else if (reCaptchaService.validToken(captcha, request.ip())) {

                    return true;
                }
                halt(428, "Unable to retrieve a validate captcha.");
                return false;

            } else {
                LOGGER.warn("No Captcha configured, request not block until reCaptcha.secret isn't configured.");
                return true;
            }
        }
        return true;
    }

    private UserDto getUserDto(User user) {
        UserDto res = new UserDto(user);
        Set<String> projectConfigIds = projectFetcher.getProjectConfigIdsByUserIdentifier(user.getIdentifier());
        List<UserProjectConfigIdDto> userProjectConfigIdDtos = new ArrayList<>();
        projectConfigIds.forEach(id -> {
            String projectId = projectFetcher.getProjectIdByProjectConfigurationId(id);
            UserProjectConfigIdDto userProjectConfigIdDto = new UserProjectConfigIdDto(id);
            userProjectConfigIdDto.setProjectId(projectId);
            userProjectConfigIdDtos.add(userProjectConfigIdDto);
        });
        res.setProjectConfigurationIds(userProjectConfigIdDtos);
        return res;
    }
}
