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


import io.kodokojo.commons.config.VersionConfig;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.model.User;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.service.healthcheck.HttpHealthCheckEndpoint;
import io.kodokojo.commons.service.lifecycle.ApplicationLifeCycleListener;
import io.kodokojo.commons.spark.SparkEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.servlet.SparkApplication;

import javax.inject.Inject;
import java.util.Set;

import static spark.Spark.*;

public class HttpEndpoint extends AbstractSparkEndpoint implements SparkApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpEndpoint.class);

    private final Set<SparkEndpoint> sparkEndpoints;

    private final VersionConfig versionConfig;

    @Inject
    public HttpEndpoint(UserAuthenticator<SimpleCredential> userAuthenticator, EventBus eventBus, EventBuilderFactory eventBuilderFactory, Set<SparkEndpoint> sparkEndpoints, VersionConfig versionConfig) {
        super(userAuthenticator, eventBus,eventBuilderFactory);
        if (sparkEndpoints == null) {
            throw new IllegalArgumentException("sparkEndpoints must be defined.");
        }
        if (versionConfig == null) {
            throw new IllegalArgumentException("versionConfig must be defined.");
        }
        this.sparkEndpoints = sparkEndpoints;
        this.versionConfig = versionConfig;
    }

    @Override
    public void configure() {

        before((request, response) -> {
            logging(request, response);
            securityCheck(request, response);
            response.type(JSON_CONTENT_TYPE);
        });

        sparkEndpoints.forEach(SparkEndpoint::configure);

        get(BASE_API, JSON_CONTENT_TYPE, (request, response) -> {
            response.type(JSON_CONTENT_TYPE);
            return "{" +
                    "\"version\":\"" + versionConfig.version() + "\"," +
                    "\"branch\":\"" + versionConfig.branch() + "\"," +
                    "\"commit\":\"" + versionConfig.gitSha1() + "\"" +
                    "}";
        });

    }

    protected void logging(Request request, Response response) {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Ip {} request url {}", request.ip(), request.url());
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Request schemas {}", request.scheme());
            }
        }

    }

    protected void securityCheck(Request request, Response response) throws Exception {
        boolean authenticationRequired = true;
        // White list of url which not require to have an identifier.
        if (requestMatch("POST", BASE_API + "/user", request) ||
                requestMatch("GET", HttpHealthCheckEndpoint.HEALTHCHECK_PATH, request) ||
                requestMatch("GET", BASE_API, request) ||
                //requestMatch("GET", BASE_API + "/event(/)?", request) ||
                requestMatch("GET", BASE_API + "/doc(/)?.*", request) ||
                requestMatch("POST", BASE_API + "/user/[^/]*", request)
                ) {
            authenticationRequired = false;
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Authentication is {}require for request {} {}.", authenticationRequired ? "" : "NOT ", request.requestMethod(), request.pathInfo());
        }
        if (authenticationRequired) {
            BasicAuthenticator basicAuthenticator = new BasicAuthenticator();
            basicAuthenticator.handle(request, response);
            if (basicAuthenticator.isProvideCredentials()) {
                User user = userAuthenticator.authenticate(new SimpleCredential(basicAuthenticator.getUsername(), basicAuthenticator.getPassword()));
                if (user == null) {
                    LOGGER.warn("ClientIp '{}' try to access to path '{}' with invalid credentials.", request.ip(), request.pathInfo());
                    authorizationRequiered(request, response);
                }
            } else {
                authorizationRequiered(request, response);
            }
        }
    }

    private static void authorizationRequiered(Request request, Response response) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Current request required an authentication which not currently provide.");
        }
        LOGGER.warn("ClientIp {} try to access to '{}' which require valid credentials.", request.ip(), request.pathInfo());
        response.header("WWW-Authenticate", "Basic realm=\"Kodokojo\"");
        response.status(401);
        halt(401);
    }

    private static boolean requestMatch(String methodName, String regexpPath, Request request) {
        boolean matchMethod = methodName.equals(request.requestMethod());
        boolean pathMatch = request.pathInfo().matches(regexpPath);
        return matchMethod && pathMatch;
    }

    @Override
    public void init() {
        configure();
    }


}
