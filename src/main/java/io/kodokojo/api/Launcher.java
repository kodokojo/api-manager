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
package io.kodokojo.api;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.kodokojo.api.config.module.DatabaseModule;
import io.kodokojo.api.config.module.HttpModule;
import io.kodokojo.api.config.module.PropertyModule;
import io.kodokojo.api.config.module.ServiceModule;
import io.kodokojo.api.config.module.endpoint.BrickEndpointModule;
import io.kodokojo.api.config.module.endpoint.ProjectEndpointModule;
import io.kodokojo.api.config.module.endpoint.UserEndpointModule;
import io.kodokojo.api.endpoint.HttpEndpoint;
import io.kodokojo.commons.config.MicroServiceConfig;
import io.kodokojo.commons.config.module.*;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.service.lifecycle.ApplicationLifeCycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Launcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(Launcher.class);

    //  WebSocket is built by Spark but we are not able to get the instance :/ .
    //  See : https://github.com/perwendel/spark/pull/383
    public static Injector INJECTOR;

    public static void main(String[] args) {


        Injector propertyInjector = Guice.createInjector(new CommonsPropertyModule(args), new PropertyModule());
        MicroServiceConfig microServiceConfig = propertyInjector.getInstance(MicroServiceConfig.class);
        LOGGER.info("Starting Kodo Kojo {}.", microServiceConfig.name());
        Injector servicesInjector = propertyInjector.createChildInjector(new UtilityServiceModule(), new EventBusModule(), new ServiceModule(), new RedisReadOnlyModule(), new SecurityModule());

        INJECTOR = servicesInjector.createChildInjector(new HttpModule(), new UserEndpointModule(), new ProjectEndpointModule(), new BrickEndpointModule());

        HttpEndpoint httpEndpoint = INJECTOR.getInstance(HttpEndpoint.class);
        EventBus eventBus = INJECTOR.getInstance(EventBus.class);
        ApplicationLifeCycleManager applicationLifeCycleManager = INJECTOR.getInstance(ApplicationLifeCycleManager.class);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                super.run();
                LOGGER.info("Stopping services.");
                applicationLifeCycleManager.stop();
                LOGGER.info("All services stopped.");
            }
        });
        applicationLifeCycleManager.addService(httpEndpoint);
        eventBus.connect();
        httpEndpoint.start();

        LOGGER.info("Kodo Kojo {} started.", microServiceConfig.name());

    }


}
