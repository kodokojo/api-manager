/**
 * Kodo Kojo - API frontend which dispatch REST event to Http services or publish event on EvetnBus.
 * Copyright © 2016 Kodo Kojo (infos@kodokojo.io)
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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.dto.ProjectConfigDto;
import io.kodokojo.commons.dto.ProjectConfigurationCreationDto;
import io.kodokojo.commons.dto.ProjectDto;
import io.kodokojo.commons.event.Event;
import io.kodokojo.commons.event.EventBuilder;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.event.payload.ProjectConfigurationChangeUserRequest;
import io.kodokojo.commons.event.payload.TypeChange;
import io.kodokojo.commons.model.Project;
import io.kodokojo.commons.model.ProjectConfiguration;
import io.kodokojo.commons.model.User;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static spark.Spark.*;

public class ProjectSparkEndpoint extends AbstractSparkEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSparkEndpoint.class);

    //private final ActorRef akkaEndpoint;

    private final ProjectFetcher projectFetcher;

    @Inject
    public ProjectSparkEndpoint(UserAuthenticator<SimpleCredential> userAuthenticator, EventBus eventBus, EventBuilderFactory eventBuilderFactory, ProjectFetcher projectFetcher) {
        super(userAuthenticator, eventBus, eventBuilderFactory);
        requireNonNull(projectFetcher, "projectFetcher must be defined.");
        this.projectFetcher = projectFetcher;

    }

    @Override
    public void configure() {
        post(BASE_API + "/projectconfig", JSON_CONTENT_TYPE, (request, response) -> {
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
            if (requester != null) {
                eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
            }
            Event reply = eventBus.request(eventBuilder.build(), 30, TimeUnit.SECONDS);
            if (reply == null) {
                halt(500,"request excess timeout.");
                return "";
            }
            String projectConfigIdentifier = reply.getPayload();

            response.status(201);
            response.header("Location", "/projectconfig/" + projectConfigIdentifier);
            return projectConfigIdentifier;
        });


        get(BASE_API + "/projectconfig/:id", JSON_CONTENT_TYPE, (request, response) -> {
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
        }, jsonResponseTransformer);

        put(BASE_API + "/projectconfig/:id/user", JSON_CONTENT_TYPE, ((request, response) -> {
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
                ProjectConfigurationChangeUserRequest projectConfigurationChangeUserRequest = new ProjectConfigurationChangeUserRequest(requester, io.kodokojo.commons.event.payload.TypeChange.ADD, identifier, userIdsToAdd);
                eventBuilder.setPayload(projectConfigurationChangeUserRequest);
                eventBus.send(eventBuilder.build());
            } else {
                halt(403, "You have not right to add user to project configuration id " + identifier + ".");
            }
            return "";
        }), jsonResponseTransformer);

        delete(BASE_API + "/projectconfig/:id/user", JSON_CONTENT_TYPE, ((request, response) -> {
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
        }), jsonResponseTransformer);

        //  -- Project

        //  Start project
        post(BASE_API + "/project/:id", JSON_CONTENT_TYPE, ((request, response) -> {
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

                    EventBuilder eventBuilder = eventBuilderFactory.create();
                    eventBuilder.setEventType(Event.PROJECTCONFIG_START_REQUEST);
                    eventBuilder.addCustomHeader(Event.REQUESTER_ID_CUSTOM_HEADER, requester.getIdentifier());
                    eventBuilder.setJsonPayload(projectConfigurationId);

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
        }));

        get(BASE_API + "/project/:id", JSON_CONTENT_TYPE, ((request, response) -> {

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
        }), jsonResponseTransformer);
    }


    private static boolean userIsUser(User user, ProjectConfiguration projectConfiguration) {
        List<User> users = IteratorUtils.toList(projectConfiguration.getUsers());
        boolean res = users.stream().filter(u -> u.getIdentifier().equals(user.getIdentifier())).findFirst().isPresent();
        return res || userIsAdmin(user, projectConfiguration);
    }

    private static boolean userIsAdmin(User user, ProjectConfiguration projectConfiguration) {
        List<User> users = IteratorUtils.toList(projectConfiguration.getAdmins());
        return users.stream().filter(u -> u.getIdentifier().equals(user.getIdentifier())).findFirst().isPresent();
    }


}

