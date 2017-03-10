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
package io.kodokojo.api.config.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.kodokojo.api.endpoint.HttpEndpoint;
import io.kodokojo.api.endpoint.JettySupport;
import io.kodokojo.api.endpoint.UserAuthenticator;
import io.kodokojo.api.endpoint.sse.SseServlet;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.config.ApplicationConfig;
import io.kodokojo.commons.config.VersionConfig;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.service.lifecycle.ApplicationLifeCycleManager;
import io.kodokojo.commons.service.repository.ProjectFetcher;
import io.kodokojo.commons.service.repository.UserFetcher;
import io.kodokojo.commons.spark.SparkEndpoint;

import java.util.Set;

public class HttpModule extends AbstractModule {

    @Override
    protected void configure() {
        // Nothing to do.
    }

    @Provides
    @Singleton
    HttpEndpoint provideHttpEndpoint(EventBus eventBus, EventBuilderFactory eventBuilderFactory, UserAuthenticator<SimpleCredential> userAuthenticator, Set<SparkEndpoint> sparkEndpoints, VersionConfig versionConfig) {
        return new HttpEndpoint(userAuthenticator, eventBus, eventBuilderFactory, sparkEndpoints, versionConfig);
    }


    @Provides
    @Singleton
    SseServlet provideSServlet(UserFetcher userFetcher, ProjectFetcher projectFetcher, EventBus eventBus) {
        SseServlet sseServlet = new SseServlet(projectFetcher, userFetcher);
        eventBus.addEventListener(sseServlet);
        return sseServlet;
    }


    @Provides
    @Singleton
    JettySupport provideJettySupport(ApplicationConfig applicationConfig, HttpEndpoint httpEndpoint, SseServlet sseServlet, ApplicationLifeCycleManager applicationLifeCycleManager) {
        JettySupport jettySupport = new JettySupport(applicationConfig.port(), httpEndpoint, sseServlet);
        applicationLifeCycleManager.addService(jettySupport);
        return jettySupport;
    }

}
