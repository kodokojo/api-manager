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

import com.google.gson.*;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.dto.*;
import io.kodokojo.commons.event.Event;
import io.kodokojo.commons.event.EventBuilder;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.event.payload.OrganisationCreationReply;
import io.kodokojo.commons.event.payload.ProjectConfigurationChangeUserRequest;
import io.kodokojo.commons.event.payload.TypeChange;
import io.kodokojo.commons.model.Project;
import io.kodokojo.commons.model.ProjectConfiguration;
import io.kodokojo.commons.model.User;
import io.kodokojo.commons.service.repository.OrganisationFetcher;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static spark.Spark.*;

public class ProjectSparkEndpoint extends AbstractSparkEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSparkEndpoint.class);

    //private final ActorRef akkaEndpoint;

    private final ProjectFetcher projectFetcher;
    private final OrganisationFetcher organisationFetcher;

    @Inject
    public ProjectSparkEndpoint(UserAuthenticator<SimpleCredential> userAuthenticator, EventBus eventBus, EventBuilderFactory eventBuilderFactory, ProjectFetcher projectFetcher, OrganisationFetcher organisationFetcher) {
        super(userAuthenticator, eventBus, eventBuilderFactory);
        requireNonNull(organisationFetcher, "organisationFetcher must be defined.");
        requireNonNull(projectFetcher, "projectFetcher must be defined.");
        this.organisationFetcher = organisationFetcher;
        this.projectFetcher = projectFetcher;
    }

    @Override
    public void configure() {
        post(BASE_API + "/projectconfig", JSON_CONTENT_TYPE, this::createProjectConfiguration);

        get(BASE_API + "/projectconfig/:id", JSON_CONTENT_TYPE, (request, response) -> getProjectConfigurationById(request), jsonResponseTransformer);

        put(BASE_API + "/projectconfig/:id/user", JSON_CONTENT_TYPE, ((request, response) -> addUserToProjectConfiguration(request)), jsonResponseTransformer);

        delete(BASE_API + "/projectconfig/:id/user", JSON_CONTENT_TYPE, ((request, response) -> deleteUser(request)), jsonResponseTransformer);

        //  -- Project

        //  Start project
        post(BASE_API + "/project/:id", JSON_CONTENT_TYPE, (this::startProject));

        get(BASE_API + "/project/:id", JSON_CONTENT_TYPE, ((request, response) -> getProjectById(request)), jsonResponseTransformer);

        //  --  Organisation

        post(BASE_API + "/organisation", JSON_CONTENT_TYPE, ((request, response) -> createOrganisation(request)), jsonResponseTransformer);

        get(BASE_API + "/organisation", JSON_CONTENT_TYPE , (request, response) -> getListOfLightOrganisationFromCurrentUser(request), jsonResponseTransformer);

        get(BASE_API + "/organisation/:id", JSON_CONTENT_TYPE, ((request, response) -> getOrganisationForCurrentUser(request)), jsonResponseTransformer);

    }

    private Object createOrganisation(Request request)  {
        String body = request.body();
        JsonParser parser = new JsonParser();
        JsonObject json = (JsonObject) parser.parse(body);
        Optional<String> nameOpt = readStringFromJson(json, "name");
        if (nameOpt.isPresent()) {
            User requester = getRequester(request);
            String name = nameOpt.get();
            EventBuilder eventBuilder = eventBuilderFactory.create();
            eventBuilder.setEventType(Event.ORGANISATION_CREATE_REQUEST);
            eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
            eventBuilder.setPayload(name);
            Event reply = null;
            try {
                reply = eventBus.request(eventBuilder.build(), 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOGGER.error("Unable to create organisation {}, timeout exceed.", name, e);
            }
            if (reply == null) {
                halt(500,"request excess timeout.");
                return "";
            }
            OrganisationCreationReply organisationCreationReply = reply.getPayload(OrganisationCreationReply.class);
            if (organisationCreationReply.isAlreadyExist()) {
                halt(409, "Organisation with name " + name + " already exist.");
                return "";
            }
            return organisationCreationReply.getIdentifier();
        }
        return "";
    }

    private Object getOrganisationForCurrentUser(Request request) {
        String identifier = request.params(":id");
        User requester = getRequester(request);
        List<UserOrganisationRightDto> organisationRightDtos = UserOrganisationRightDto.computeUserOrganisationRights(requester, organisationFetcher, projectFetcher);
        Optional<UserOrganisationRightDto> userOrganisationRightDto = organisationRightDtos.stream()
                .filter(organisation -> organisation.getIdentifier().equals(identifier))
                .findFirst();
        if (userOrganisationRightDto.isPresent()) {
            return userOrganisationRightDto.get();
        }
        halt(404, "not able to found or you don't have right to acces to organisation with id " + identifier);
        return "";
    }

    private Object getListOfLightOrganisationFromCurrentUser(Request request) {
        User requester = getRequester(request);
        List<UserOrganisationRightDto> organisationRightDtos = UserOrganisationRightDto.computeUserOrganisationRights(requester, organisationFetcher, projectFetcher);
        return organisationRightDtos.stream()
                .map(organisation -> new OrganisationLightDto(organisation.getIdentifier(), organisation.getName()))
                .collect(Collectors.toList());
    }

    private Object createProjectConfiguration(Request request, Response response) throws InterruptedException {
        User requester = getRequester(request);

        String body = request.body();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Try to create project configuration {}", body);
        }
        Gson gson = localGson.get();
        ProjectConfigurationCreationDto dto = gson.fromJson(body, ProjectConfigurationCreationDto.class);
        if (dto == null) {
            halt(400);
            return "";
        }

        EventBuilder eventBuilder = eventBuilderFactory.create();
        eventBuilder.setEventType(Event.PROJECTCONFIG_CREATION_REQUEST);
        eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
        eventBuilder.setPayload(dto);

        Event reply = eventBus.request(eventBuilder.build(), 30, TimeUnit.SECONDS);
        if (reply == null) {
            halt(500,"request excess timeout.");
            return "";
        }
        String projectConfigIdentifier = reply.getPayload();

        response.status(201);
        response.header("Location", "/projectconfig/" + projectConfigIdentifier);
        return projectConfigIdentifier;
    }

    private Object getProjectConfigurationById(Request request) {
        String identifier = request.params(":id");
        ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(identifier);
        if (projectConfiguration == null) {
            halt(404);
            return "";
        }
        User requester = getRequester(request);

        if (userIsUser(requester, projectConfiguration)) {
            return new ProjectConfigDto(projectConfiguration);
        } else {
            halt(403, "You have not right to lookup projectConfiguration id " + identifier + ".");
        }
        return "";
    }

    private Object addUserToProjectConfiguration(Request request) {
        User requester = getRequester(request);

        String identifier = request.params(":id");

        ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(identifier);

        if (projectConfiguration == null) {
            halt(404);
            return "";
        }

        if (userIsAdmin(requester, projectConfiguration)) {

            List<String> userIdsToAdd = new ArrayList<>();
            JsonParser parser = new JsonParser();
            JsonArray root = (JsonArray) parser.parse(request.body());
            for (JsonElement el : root) {
                String userToAddId = el.getAsJsonPrimitive().getAsString();
                userIdsToAdd.add(userToAddId);
            }

            EventBuilder eventBuilder = eventBuilderFactory.create();
            eventBuilder.setEventType(Event.PROJECTCONFIG_CHANGE_USER_REQUEST);
            eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
            ProjectConfigurationChangeUserRequest projectConfigurationChangeUserRequest = new ProjectConfigurationChangeUserRequest(requester, TypeChange.ADD, identifier, userIdsToAdd);
            eventBuilder.setPayload(projectConfigurationChangeUserRequest);
            eventBus.send(eventBuilder.build());
        } else {
            halt(403, "You have not right to add user to project configuration id " + identifier + ".");
        }
        return "";
    }

    private Object deleteUser(Request request) {
        User requester = getRequester(request);

        String identifier = request.params(":id");

        ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(identifier);
        if (projectConfiguration == null) {
            halt(404);
            return "";
        }

        if (userIsAdmin(requester, projectConfiguration)) {

            List<String> userIdsToRemove = new ArrayList<>();
            JsonParser parser = new JsonParser();
            JsonArray root = (JsonArray) parser.parse(request.body());
            for (JsonElement el : root) {
                String userToAddId = el.getAsJsonPrimitive().getAsString();
                userIdsToRemove.add(userToAddId);
            }
            EventBuilder eventBuilder = eventBuilderFactory.create();
            eventBuilder.setEventType(Event.PROJECTCONFIG_CHANGE_USER_REQUEST);
            eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
            ProjectConfigurationChangeUserRequest projectConfigurationChangeUserRequest = new ProjectConfigurationChangeUserRequest(requester, TypeChange.REMOVE, identifier, userIdsToRemove);
            eventBuilder.setPayload(projectConfigurationChangeUserRequest);
            eventBus.send(eventBuilder.build());
        } else {
            halt(403, "You have not right to add user to project configuration id " + identifier + ".");

        }
        return "";
    }

    private Object startProject(Request request, Response response) throws InterruptedException {
        User requester = getRequester(request);
        String projectConfigurationId = request.params(":id");
        ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(projectConfigurationId);
        if (projectConfiguration == null) {
            halt(404, "Project configuration not found.");
            return "";
        }
        if (userIsAdmin(requester, projectConfiguration)) {
            String projectId = projectFetcher.getProjectIdByProjectConfigurationId(projectConfigurationId);
            if (StringUtils.isBlank(projectId)) {

                EventBuilder eventBuilder = eventBuilderFactory.create()
                        .setEventType(Event.PROJECTCONFIG_START_REQUEST)
                        .addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier())
                        .setJsonPayload(projectConfigurationId);

                Event reply = eventBus.request(eventBuilder.build(), 1, TimeUnit.MINUTES);

                if (reply != null) {

                    response.status(201);
                    String projectIdStarted = reply.getPayload();
                    return projectIdStarted;
                } else {
                    halt(408, "Unable to know if project " + projectConfigurationId + " had been started or not.");
                    return "";
                }
            } else {
                halt(409, "Project already exist.");
            }
        } else {
            halt(403, "You have not right to start project configuration id " + projectConfigurationId + ".");
        }
        return "";
    }

    private Object getProjectById(Request request) {
        User requester = getRequester(request);
        String projectId = request.params(":id");
        Project project = projectFetcher.getProjectByIdentifier(projectId);
        if (project == null) {
            halt(404);
            return "";
        }
        ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(project.getProjectConfigurationIdentifier());
        if (userIsUser(requester, projectConfiguration)) {
            ProjectDto projectDto = new ProjectDto(project);
            return projectDto;
        } else {
            halt(403, "You have not right to lookup project id " + projectId + ".");
        }

        return "";
    }


    private static boolean userIsUser(User user, ProjectConfiguration projectConfiguration) {
        List<User> users = IteratorUtils.toList(projectConfiguration.getUsers());
        boolean res = users.stream().anyMatch(u -> u.getIdentifier().equals(user.getIdentifier()));
        return res || userIsAdmin(user, projectConfiguration);
    }

    private static boolean userIsAdmin(User user, ProjectConfiguration projectConfiguration) {
        List<User> users = IteratorUtils.toList(projectConfiguration.getTeamLeaders());
        return users.stream().anyMatch(u -> u.getIdentifier().equals(user.getIdentifier()));
    }


}

