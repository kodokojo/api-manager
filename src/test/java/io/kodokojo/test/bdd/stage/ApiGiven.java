package io.kodokojo.test.bdd.stage;

import com.google.inject.*;
import com.tngtech.jgiven.annotation.*;
import io.kodokojo.api.Launcher;
import io.kodokojo.api.config.ReCaptchaConfig;
import io.kodokojo.api.config.module.HttpModule;
import io.kodokojo.api.config.module.ServiceModule;
import io.kodokojo.api.config.module.SimpleUserAuthenticatorProvider;
import io.kodokojo.api.config.module.endpoint.ProjectEndpointModule;
import io.kodokojo.api.config.module.endpoint.UserEndpointModule;
import io.kodokojo.api.endpoint.HttpEndpoint;
import io.kodokojo.api.endpoint.UserAuthenticator;
import io.kodokojo.api.service.authentification.SimpleCredential;
import io.kodokojo.commons.config.ApplicationConfig;
import io.kodokojo.commons.config.MicroServiceConfig;
import io.kodokojo.commons.config.VersionConfig;
import io.kodokojo.commons.config.module.RedisReadOnlyModule;
import io.kodokojo.test.DockerTestSupport;
import io.kodokojo.test.TestSecurityModule;
import io.kodokojo.test.TestUtils;
import okhttp3.OkHttpClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ApiGiven<SELF extends ApiGiven<?>> extends ApplicationGiven<SELF> {

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
                        return TestUtils.getEphemeralPort();
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
/*
            @Provides
            @Singleton
            BrickUrlFactory provideBrickUrlFactory() {
                return new DefaultBrickUrlFactory("kodokojo.dev");
            }


            @Provides
            @Singleton
            BrickStateEventDispatcher provideBrickStateEventDispatcher() {
                return new BrickStateEventDispatcher();
            }
*/
        };

        injector = Guice.createInjector(eventBusModule, redisModule, versionModule, new TestSecurityModule(), new ServiceModule(),
                new HttpModule(), new UserEndpointModule(), new ProjectEndpointModule(),
                new RedisReadOnlyModule());
        Launcher.INJECTOR = injector;
        HttpEndpoint httpEndpoint = Launcher.INJECTOR.getInstance(HttpEndpoint.class);
        restEntryPointHost = "localhost";
        restEntryPointPort = httpEndpoint.getPort();

        httpEndpoint.start();
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

    @AfterScenario
    public void tear_down() {
        if (injector != null) {
            HttpEndpoint httpEndpoint = injector.getInstance(HttpEndpoint.class);
            if (httpEndpoint != null) {
                httpEndpoint.stop();
            }
        }
    }

}
