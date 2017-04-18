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

import com.google.gson.*;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.dto.*;
import io.kodokojo.commons.event.Event;
import io.kodokojo.commons.event.EventBuilder;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.event.payload.OrganisationChangeUserRequest;
import io.kodokojo.commons.event.payload.OrganisationCreationReply;
import io.kodokojo.commons.event.payload.ProjectConfigurationChangeUserRequest;
import io.kodokojo.commons.event.payload.TypeChange;
import io.kodokojo.commons.model.*;
import io.kodokojo.commons.service.actor.message.BrickStateEvent;
import io.kodokojo.commons.service.repository.OrganisationFetcher;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import org.apache.commons.collections4.IteratorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang.StringUtils.isBlank;
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

        patch(BASE_API + "/project/:id/:stackName/:brickName/:state", JSON_CONTENT_TYPE, (request, response) -> updateBrickState(request), jsonResponseTransformer);

        //  --  Organisation

        post(BASE_API + "/organisation", JSON_CONTENT_TYPE, (this::createOrganisation), jsonResponseTransformer);

        get(BASE_API + "/organisation", JSON_CONTENT_TYPE, (request, response) -> getListOfLightOrganisationFromCurrentUser(request), jsonResponseTransformer);

        get(BASE_API + "/organisation/:id", JSON_CONTENT_TYPE, ((request, response) -> getOrganisationForCurrentUser(request)), jsonResponseTransformer);

        put(BASE_API + "/organisation/:id/admin", JSON_CONTENT_TYPE, (request, response) -> {
            OrganisationChangeUserRequest.TypeChange typeChange = OrganisationChangeUserRequest.TypeChange.ADD;
            return organisationChangeAdmin(request, typeChange);
        }, jsonResponseTransformer);

        delete(BASE_API + "/organisation/:id/admin", JSON_CONTENT_TYPE, (request, response) -> {
            OrganisationChangeUserRequest.TypeChange typeChange = OrganisationChangeUserRequest.TypeChange.REMOVE;
            return organisationChangeAdmin(request, typeChange);
        }, jsonResponseTransformer);

    }

    private Object updateBrickState(Request request) {
        String identifier = request.params(":id");
        String stackName = request.params(":stackName");
        String brickName = request.params(":brickName");
        String stateParam = request.params(":state");
        if (isBlank(identifier) || isBlank(stackName) || isBlank(brickName) || isBlank(stateParam)) {
            halt(400, "Invalid parameter to change brick state.");
        }
        User requester = getRequester(request);

        Project project = projectFetcher.getProjectByIdentifier(identifier);
        if (project == null) {
            halt(404, "Unable to found project with id '" + identifier + "'.");
            return "";
        }

        ProjectConfiguration projectConfiguration = projectFetcher.getProjectConfigurationById(project.getProjectConfigurationIdentifier());
        if (projectConfiguration == null) {
            LOGGER.error("Unable to found projectConfiguration with id '{}' from project '{}' with id '{}'.", project.getProjectConfigurationIdentifier(), project.getName(), identifier);
            halt(500, "Unable to found a projectConfiguration for project with id " + identifier);
            return "";
        }

        Organisation organisation = organisationFetcher.getOrganisationById(projectConfiguration.getEntityIdentifier());
        if (organisation == null) {
            LOGGER.error("Unable to found organisation with id '{}' from project '{}' with id '{}'.", projectConfiguration.getEntityIdentifier(), project.getName(), identifier);
            halt(500, "Unable to found a organisation for project with id " + identifier);
            return "";
        }

        if (requester.isRoot() && organisation.userIsAdmin(requester.getIdentifier())) {

            Iterator<BrickConfiguration> defaultBrickConfigurations = projectConfiguration.getDefaultBrickConfigurations();
            BrickConfiguration brickConfiguration = null;
            while (defaultBrickConfigurations.hasNext() && brickConfiguration == null) {
                BrickConfiguration current = defaultBrickConfigurations.next();
                if (current.getName().equals(brickName)) {
                    brickConfiguration = current;
                }
            }
            if (brickConfiguration == null) {
                halt(404, "Unable to found brick named '" + brickName + "' in project '" + project.getName() + "'.");
                return "";
            }
            String brickType = brickConfiguration.getType().name();
            String version = brickConfiguration.getVersion();

            EventBuilder eventBuilder = eventBuilderFactory.create();
            eventBuilder.setEventType(Event.BRICK_STATE_UPDATE);
            BrickStateEvent brickStateEvent = new BrickStateEvent(projectConfiguration.getIdentifier(),
                    stackName,
                    brickType,
                    brickName,
                    BrickStateEvent.State.valueOf(stateParam),
                    version
            );
            eventBuilder.setPayload(brickStateEvent);
            eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
            eventBus.send(eventBuilder.build());
        } else {
            halt(403, "You aren't alloed to change state of this project.");
        }

        return "";
    }

    private Object organisationChangeAdmin(Request request, OrganisationChangeUserRequest.TypeChange typeChange) {
        User requester = getRequester(request);
        String identifier = request.params(":id");
        Organisation organisation = organisationFetcher.getOrganisationById(identifier);
        if (organisation == null) {
            halt(404, "Organisation with id '" + identifier + "' not found.");
            return "";
        }
        if (requester.isRoot() || organisation.userIsAdmin(requester.getIdentifier())) {
            List<String> userIdsToAdd = new ArrayList<>();
            JsonParser parser = new JsonParser();
            JsonArray root = (JsonArray) parser.parse(request.body());
            for (JsonElement el : root) {
                String userToAddId = el.getAsJsonPrimitive().getAsString();
                userIdsToAdd.add(userToAddId);
            }
            EventBuilder eventBuilder = eventBuilderFactory.create();
            eventBuilder.setEventType(Event.ORGANISATION_CHANGE_ADMIN_REQUEST);
            eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
            userIdsToAdd.forEach(userId -> {
                OrganisationChangeUserRequest payload = new OrganisationChangeUserRequest(requester, typeChange, organisation.getIdentifier(), userId);
                eventBuilder.setPayload(payload);
                eventBus.send(eventBuilder.build());
            });
        } else {
            halt(403);
            return "You aren't allowed to " + typeChange + " admin to this organisation.";
        }
        return "";
    }

    private Object createOrganisation(Request request, Response response) {
        User requester = getRequester(request);
        if (!requester.isRoot()) {
            halt(403, "You aren't allow to create organisation.");
            return "";
        }
        String body = request.body();
        JsonParser parser = new JsonParser();
        JsonObject json = (JsonObject) parser.parse(body);
        Optional<String> nameOpt = readStringFromJson(json, "name");
        if (nameOpt.isPresent()) {
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
                halt(500, "request excess timeout.");
                return "";
            }
            OrganisationCreationReply organisationCreationReply = reply.getPayload(OrganisationCreationReply.class);
            if (organisationCreationReply.isAlreadyExist()) {
                halt(409, "Organisation with name " + name + " already exist.");
                return "";
            }
            response.status(201);
            return new OrganisationLightDto(organisationCreationReply.getIdentifier(), name);
        } else {
            halt(400, "name is required.");
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
            halt(500, "request excess timeout.");
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

        Organisation organisation = organisationFetcher.getOrganisationById(projectConfiguration.getEntityIdentifier());

        if (organisation.userIsAdmin(requester.getIdentifier()) || userIsUser(requester, projectConfiguration)) {
            ProjectConfigDto projectConfigDto = new ProjectConfigDto(projectConfiguration);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("ProjectConfiguration with Id '{}':\n{}", identifier, new GsonBuilder().setPrettyPrinting().create().toJson(projectConfigDto));
            }
            return projectConfigDto;
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
        Organisation organisation = organisationFetcher.getOrganisationById(projectConfiguration.getEntityIdentifier());

        if (organisation.userIsAdmin(requester.getIdentifier()) ||userIsAdmin(requester, projectConfiguration)) {

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

        Organisation organisation = organisationFetcher.getOrganisationById(projectConfiguration.getEntityIdentifier());

        if (organisation.userIsAdmin(requester.getIdentifier()) ||userIsAdmin(requester, projectConfiguration)) {

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
            if (isBlank(projectId)) {

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
        Organisation organisation = organisationFetcher.getOrganisationById(projectConfiguration.getEntityIdentifier());
        if (organisation.userIsAdmin(requester.getIdentifier()) || userIsUser(requester, projectConfiguration)) {
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

