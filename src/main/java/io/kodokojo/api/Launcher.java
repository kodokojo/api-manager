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
package io.kodokojo.api;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.multibindings.Multibinder;
import io.kodokojo.api.config.module.HttpModule;
import io.kodokojo.api.config.module.PropertyModule;
import io.kodokojo.api.config.module.ServiceModule;
import io.kodokojo.api.config.module.endpoint.BrickEndpointModule;
import io.kodokojo.api.config.module.endpoint.ProjectEndpointModule;
import io.kodokojo.api.config.module.endpoint.UserEndpointModule;
import io.kodokojo.api.endpoint.HttpEndpoint;
import io.kodokojo.api.endpoint.JettySupport;
import io.kodokojo.commons.config.MicroServiceConfig;
import io.kodokojo.commons.config.module.*;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.service.healthcheck.HttpHealthCheckEndpoint;
import io.kodokojo.commons.service.lifecycle.ApplicationLifeCycleManager;
import io.kodokojo.commons.spark.SparkEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Launcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(Launcher.class);

    public Injector injector;

    public static void main(String[] args) {
        Launcher launcher = new Launcher();
        launcher.start(args);
    }

    public void start(String[] args) {


        Injector propertyInjector = Guice.createInjector(new CommonsPropertyModule(args), new PropertyModule());
        MicroServiceConfig microServiceConfig = propertyInjector.getInstance(MicroServiceConfig.class);
        LOGGER.info("Starting Kodo Kojo {}.", microServiceConfig.name());
        Injector servicesInjector = propertyInjector.createChildInjector(
                new UtilityServiceModule(),
                new EventBusModule(),
                new ServiceModule(),
                new RedisReadOnlyModule(),
                new SecurityModule(),
                new CommonsHealthCheckModule()
        );

        injector = servicesInjector.createChildInjector(
                new HttpModule(),
                new UserEndpointModule(),
                new ProjectEndpointModule(),
                new BrickEndpointModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        Multibinder<SparkEndpoint> sparkEndpointBinder = Multibinder.newSetBinder(binder(), SparkEndpoint.class);
                        sparkEndpointBinder.addBinding().to(HttpHealthCheckEndpoint.class);
                    }
                }
        );


        EventBus eventBus = injector.getInstance(EventBus.class);
        ApplicationLifeCycleManager applicationLifeCycleManager = injector.getInstance(ApplicationLifeCycleManager.class);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                super.run();
                LOGGER.info("Stopping services.");
                applicationLifeCycleManager.stop();
                LOGGER.info("All services stopped.");
            }
        });
        JettySupport jettySupport = injector.getInstance(JettySupport.class);

        eventBus.connect();
        jettySupport.start();

        LOGGER.info("Kodo Kojo {} started.", microServiceConfig.name());
    }


}
