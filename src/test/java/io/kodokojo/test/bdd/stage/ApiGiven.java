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
package io.kodokojo.test.bdd.stage;

import com.google.inject.*;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.*;
import io.kodokojo.api.config.ReCaptchaConfig;
import io.kodokojo.api.config.module.HttpModule;
import io.kodokojo.api.config.module.ServiceModule;
import io.kodokojo.api.config.module.SimpleUserAuthenticatorProvider;
import io.kodokojo.api.config.module.endpoint.ProjectEndpointModule;
import io.kodokojo.api.config.module.endpoint.UserEndpointModule;
import io.kodokojo.api.endpoint.JettySupport;
import io.kodokojo.api.endpoint.UserAuthenticator;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.config.*;
import io.kodokojo.commons.config.module.RedisReadOnlyModule;
import io.kodokojo.commons.config.module.UtilityServiceModule;
import io.kodokojo.commons.event.DefaultEventBuilderFactory;
import io.kodokojo.commons.event.EventBuilderFactory;
import io.kodokojo.commons.event.EventBus;
import io.kodokojo.commons.event.JsonToEventConverter;
import io.kodokojo.commons.model.ServiceInfo;
import io.kodokojo.commons.rabbitmq.RabbitMqConnectionFactory;
import io.kodokojo.commons.rabbitmq.RabbitMqEventBus;
import io.kodokojo.test.*;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Spark;
import spark.route.Routes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ApiGiven<SELF extends ApiGiven<?>> extends Stage<SELF> implements DockerTestApplicationBuilder, JsonToEventConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiGiven.class);

    private static final Map<String, String> USER_PASSWORD = new HashMap<>();

    static {
        USER_PASSWORD.put("jpthiery", "jpascal");
    }

    @ProvidedScenarioState
    public DockerTestSupport dockerTestSupport;

    @ProvidedScenarioState
    String redisHost;

    @ProvidedScenarioState
    int redisPort;

    @ProvidedScenarioState
    String restEntryPointHost;

    @ProvidedScenarioState
    int restEntryPointPort;

    @ProvidedScenarioState
    String currentUserLogin;

    @ProvidedScenarioState
    Map<String, UserInfo> currentUsers = new HashMap<>();

    @ProvidedScenarioState
    HttpUserSupport httpUserSupport;

    @ProvidedScenarioState(resolution = ScenarioState.Resolution.NAME)
    Module versionModule;

    @ProvidedScenarioState
    Injector injector;

    @ProvidedScenarioState
    boolean waitingList = false;

    private JettySupport jettySupport;

    private Module eventBusModule;

    private Module redisModule;

    @BeforeScenario
    public void setup() {

        injector = null;
        microServiceTesterMock = null;
        eventBusModule = null;
        redisModule = null;

    }

    @AfterScenario
    public void tear_down() {
        if (jettySupport != null) {
            jettySupport.stop();
            jettySupport = null;
        }

        try {
            //remove routes from static instances of Spark...

            //  Access to a static private instance from a private static method
            Class<?> sparkClass = Class.forName(Spark.class.getCanonicalName());
            Method method = sparkClass.getDeclaredMethod("getInstance");
            method.setAccessible(true);
            Object sparkService = method.invoke(sparkClass);

            //  Access to a protected field routes
            Class<?> sparkInstanceClass = Class.forName("spark.Service");
            Arrays.stream(sparkInstanceClass.getDeclaredFields())
                    .filter(f -> "routes".equals(f.getName()))
                    .findFirst()
                    .ifPresent(f -> {
                        // Access to the field routes and call clear() on it.
                        f.setAccessible(true);
                        try {
                            Routes routes  = (Routes) f.get(sparkService);
                            if (routes != null) {
                                routes.clear();
                            }
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                        LOGGER.info("Spark Routes cleared !!");
                    });


        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        injector = null;
        microServiceTesterMock = null;
        eventBusModule = null;
        redisModule = null;

    }

    public SELF api_is_ready(@Hidden DockerTestSupport dockerTestSupport) {
        String uuid = UUID.randomUUID().toString();
        MicroServiceConfig microServiceConfig = new MicroServiceConfig() {
            @Override
            public String name() {
                return "api";
            }

            @Override
            public String uuid() {
                return uuid;
            }
        };
        all_middlewares_are_ready(dockerTestSupport, microServiceConfig);

        restEntryPointPort = TestUtils.getEphemeralPort();

        versionModule = new AbstractModule() {
            @Override
            protected void configure() {
                bind(new TypeLiteral<UserAuthenticator<SimpleCredential>>() {/**/
                }).toProvider(SimpleUserAuthenticatorProvider.class);
            }

            @Provides
            @Singleton
            VersionConfig provideVersionConfig() {
                return new VersionConfig() {
                    @Override
                    public String version() {
                        return "1.0.0";
                    }

                    @Override
                    public String gitSha1() {
                        return "c23abd124";
                    }

                    @Override
                    public String branch() {
                        return "test";
                    }
                };
            }

            @Provides
            @Singleton
            ApplicationConfig provideApplicationConfig() {
                return new ApplicationConfig() {
                    @Override
                    public int port() {
                        return restEntryPointPort;
                    }

                    @Override
                    public String domain() {
                        return "kodokojo.dev";
                    }

                    @Override
                    public String loadbalancerHost() {
                        return null;
                    }

                    @Override
                    public int initialSshPort() {
                        return 0;
                    }

                    @Override
                    public long sslCaDuration() {
                        return 0;
                    }

                    @Override
                    public Boolean userCreationRoutedInWaitingList() {
                        return false;
                    }
                };
            }

            @Provides
            @Singleton
            ReCaptchaConfig provideReCaptchaConfig() {
                return new ReCaptchaConfig() {
                    @Override
                    public String secret() {
                        return "";
                    }
                };
            }

            @Provides
            @Singleton
            ElasticSearchConfig provideElasticSearchConfig() {
                return new ElasticSearchConfig() {

                    @Override
                    public String url() {
                        return null;
                    }

                    @Override
                    public String indexName() {
                        return null;
                    }
                };
            }

        };

        injector = Guice.createInjector(eventBusModule, redisModule, versionModule, new TestSecurityModule(), new ServiceModule(), new UtilityServiceModule(),
                new HttpModule(), new UserEndpointModule(), new ProjectEndpointModule(),
                new RedisReadOnlyModule());

        restEntryPointHost = "localhost";

        jettySupport = injector.getInstance(JettySupport.class);
        jettySupport.start();
        httpUserSupport = new HttpUserSupport(new OkHttpClient(), restEntryPointHost + ":" + restEntryPointPort, injector, microServiceTesterMock);

        return self();
    }

    public SELF waiting_list_is_activated() {
        waitingList = true;
        return self();
    }


    public SELF i_will_be_user_$(@Quoted String username) {
        return i_am_user_$(username, false);
    }

    public SELF i_am_user_$(@Quoted String username, @Hidden boolean createUser) {
        currentUserLogin = username;
        if (createUser) {

            UserInfo userCreated = httpUserSupport.createUser(null, username + "@kodokojo.io");
            currentUsers.put(userCreated.getUsername(), userCreated);
        }
        return self();
    }

    @ProvidedScenarioState
    MicroServiceTesterMock microServiceTesterMock;

    public SELF all_middlewares_are_ready(@Hidden DockerTestSupport dockerTestSupport, @Hidden MicroServiceConfig microServiceConfig) {
        this.dockerTestSupport = dockerTestSupport;
        event_bus_is_available(dockerTestSupport, microServiceConfig);
        redis_is_started(dockerTestSupport);
        return self();
    }

    public SELF event_bus_is_available(@Hidden DockerTestSupport dockerTestSupport, @Hidden MicroServiceConfig microServiceConfig) {
        this.dockerTestSupport = dockerTestSupport;
        DockerService rabbitMq = startRabbitMq(dockerTestSupport).get();

        RabbitMqConfig rabbitMqConfig = new TestRabbitMqConfig(rabbitMq, microServiceConfig.name());

        eventBusModule = new AbstractModule() {
            @Override
            protected void configure() {
                //
            }

            @Singleton
            @Provides
            EventBuilderFactory provideEventBuilderFactory() {
                return new DefaultEventBuilderFactory(microServiceConfig);
            }

            @Provides
            @Singleton
            EventBus provideRabbitMqEventBus(VersionConfig versionConfig) {
                ServiceInfo serviceInfo = new ServiceInfo(microServiceConfig.name(), microServiceConfig.uuid(), versionConfig.version(), versionConfig.gitSha1(), versionConfig.branch());
                RabbitMqEventBus rabbitMqEventBus = new RabbitMqEventBus(rabbitMqConfig, new RabbitMqConnectionFactory() {
                }, new JsonToEventConverter() {
                }, microServiceConfig, serviceInfo);
                rabbitMqEventBus.connect();
                return rabbitMqEventBus;
            }

        };
        microServiceTesterMock = MicroServiceTesterMock.getInstance(rabbitMqConfig);
        microServiceTesterMock.connect();
        return self();
    }

    public SELF redis_is_started(@Hidden DockerTestSupport dockerTestSupport) {
        this.dockerTestSupport = dockerTestSupport;
        DockerService service = startRedis(dockerTestSupport).get();
        redisHost = service.getHost();
        redisPort = service.getPortDefinition().getContainerPort();

        redisModule = new AbstractModule() {
            @Override
            protected void configure() {
                //
            }

            @Provides
            @Singleton
            RedisConfig provideRedisConfig() {
                return new RedisConfig() {
                    @Override
                    public String host() {
                        return redisHost;
                    }

                    @Override
                    public Integer port() {
                        return redisPort;
                    }

                    @Override
                    public String password() {
                        return null;
                    }
                };
            }
        };

        return self();
    }


}
