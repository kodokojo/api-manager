/**
 * Kodo Kojo - API frontend which dispatch REST event to Http services or publish event on EvetnBus.
 * Copyright © 2017 Kodo Kojo (infos@kodokojo.io)
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
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
import io.kodokojo.commons.dto.UserOrganisationRightDto;
import io.kodokojo.commons.dto.UserUpdateDto;
import io.kodokojo.commons.event.Event;
import io.kodokojo.commons.event.EventBuilder;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.event.payload.UserCreationReply;
import io.kodokojo.commons.event.payload.UserCreationRequest;
import io.kodokojo.commons.model.User;
import io.kodokojo.commons.service.repository.OrganisationFetcher;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import io.kodokojo.commons.service.repository.UserFetcher;
import io.kodokojo.commons.service.repository.UserSearcher;
import io.kodokojo.commons.service.repository.search.Criteria;
import io.kodokojo.commons.service.repository.search.UserSearchDto;
import io.kodokojo.commons.spark.JsonTransformer;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang.StringUtils.isBlank;
import static spark.Spark.*;

public class UserSparkEndpoint extends AbstractSparkEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserSparkEndpoint.class);

    private final UserFetcher userFetcher;

    private final OrganisationFetcher organisationFetcher;

    private final ProjectFetcher projectFetcher;

    private final ReCaptchaService reCaptchaService;

    private final UserSearcher userSearcher;

    @Inject
    public UserSparkEndpoint(UserAuthenticator<SimpleCredential> userAuthenticator,
                             EventBus eventBus,
                             EventBuilderFactory eventBuilderFactory,
                             UserFetcher userFetcher,
                             OrganisationFetcher organisationFetcher,
                             ProjectFetcher projectFetcher,
                             UserSearcher userSearcher,
                             ReCaptchaService reCaptchaService
    ) {
        super(userAuthenticator, eventBus, eventBuilderFactory);
        requireNonNull(userFetcher, "userFetcher must be defined.");
        requireNonNull(organisationFetcher, "entityFetcher must be defined.");
        requireNonNull(projectFetcher, "projectFetcher must be defined.");
        requireNonNull(reCaptchaService, "reCaptchaService must be defined.");
        requireNonNull(userSearcher, "userSearcher must be defined.");
        this.organisationFetcher = organisationFetcher;
        this.userFetcher = userFetcher;
        this.projectFetcher = projectFetcher;
        this.reCaptchaService = reCaptchaService;
        this.userSearcher = userSearcher;
    }

    @Override
    public void configure() {
        patch(BASE_API + "/user/:id", JSON_CONTENT_TYPE,
                (request, response) -> updateUser(request),
                jsonResponseTransformer
        );

        post(BASE_API + "/user/:id", JSON_CONTENT_TYPE,
                this::createUser,
                jsonResponseTransformer
        );

        post(BASE_API + "/user",
                JSON_CONTENT_TYPE,
                (request, response) -> requestNewIdentifier()
        );

        get(BASE_API + "/user", JSON_CONTENT_TYPE, (request, response) -> {
            User requester = getRequester(request);
            return getUserDto(requester);
        }, jsonResponseTransformer);

        get(BASE_API + "/user/search", JSON_CONTENT_TYPE, (request, response) -> searchUser(request), jsonResponseTransformer);

        get(BASE_API + "/user/:id", JSON_CONTENT_TYPE,
                (request, response) -> getUserById(request),
                jsonResponseTransformer
        );
    }

    private Object searchUser(Request request) {
        User requester = getRequester(request);
        String criteria = request.queryParams("q");
        if (isBlank(criteria)) {
            halt(400, "'q' criteria parameter must be defined.");
            return "";
        }
        return userSearcher.searchUserByCriterion(requester.getOrganisationIds(), new Criteria("global", criteria)).getOrElse(new ArrayList<>());
    }

    private Object getUserById(Request request) {
        User requester = getRequester(request);
        String identifier = request.params(":id");
        User user = userFetcher.getUserByIdentifier(identifier);
        if (user != null) {
            if (user.getOrganisationIds().equals(requester.getOrganisationIds())) {
                if (!user.equals(requester)) {
                    user = new User(user.getIdentifier(), user.getOrganisationIds(), user.getName(), user.getUsername(), user.getEmail(), "", user.getSshPublicKey(), user.isRoot());
                }
                return getUserDto(user);
            }
            halt(403, "You aren't in same organisation.");
            return "";
        }
        halt(404);
        return "";
    }

    private Object requestNewIdentifier() throws InterruptedException {
        LOGGER.debug("Require a new user Identifier.");
        LOGGER.info("Requiere new id via eventBus {} [{}]", eventBus, this);
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
    }

    private Object createUser(Request request, Response response) throws InterruptedException {
        String identifier = request.params(":id");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Try to create user with id {}", identifier);
        }

        User requester = getRequester(request);
        if (!validateThrowCaptcha(request, requester)) return "";

        JsonParser parser = new JsonParser();
        JsonObject json = (JsonObject) parser.parse(request.body());
        String email = readStringFromJson(json, "email").get();
        String username = email.substring(0, email.lastIndexOf("@"));
        Boolean isRoot = readBooleanFromJson(json, "isRoot").orElse(Boolean.FALSE);

        if (isRoot) {
            if (requester == null || !requester.isRoot()) {
                halt(403, "You must be a valid root user to be able to create a root user.");
                return "";
            }
        }

        String organisationId = "";
        if (requester != null) {
            organisationId = readStringFromJson(json, "organisationId").orElse("");
            if (!requester.isRoot() && !requester.getOrganisationIds().contains(organisationId)) {
                halt(404, "Unable to create user for not authorized or unknow organisation " + organisationId);
                return "";
            }
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Create a new User with a new Entity");
        }

        EventBuilder eventBuilder = eventBuilderFactory.create();
        eventBuilder.setEventType(Event.USER_CREATION_REQUEST);
        if (requester != null) {
            eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
        }
        eventBuilder.setPayload(new UserCreationRequest(identifier, email, username, organisationId, isRoot));

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
                UserCreationDto res = new UserCreationDto(user, userCreationReply.getPrivateKey());
                res.setOrganisations(UserOrganisationRightDto.computeUserOrganisationRights(user, organisationFetcher, projectFetcher));
                return res;
            }
        }

        halt(500, "An unexpected behaviour happened while trying to create user " + username + ".");
        return "";
    }

    private Object updateUser(Request request) throws InterruptedException {
        String identifier = request.params(":id");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Try to update user with id {}", identifier);
        }

        User requester = getRequester(request);
        User user = userFetcher.getUserByIdentifier(identifier);

        if (requester.getIdentifier().equals(user.getIdentifier())) {


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
                JsonParser parser = new JsonParser();
                String replyPayload = reply.getPayload();
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
    }

    private boolean validateThrowCaptcha(Request request, User requester) {
        if (requester == null) {
            if (reCaptchaService.isConfigured()) {
                String captcha = request.headers("g-recaptcha-response");
                if (isBlank(captcha)) {
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

        List<UserOrganisationRightDto> userOrganisationRightDtos = UserOrganisationRightDto.computeUserOrganisationRights(user, organisationFetcher, projectFetcher);

        res.setOrganisations(userOrganisationRightDtos);

        return res;
    }


}
