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
package io.kodokojo.bdd.stage;

import com.google.gson.*;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.kodokojo.commons.dto.BrickConfigDto;
import io.kodokojo.test.bdd.stage.HttpUserSupport;
import io.kodokojo.test.bdd.stage.UserInfo;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.fail;

public class AccessRestWhen<SELF extends AccessRestWhen<?>> extends Stage<SELF> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccessRestWhen.class);

    private static final String SUCCESS_REGISTRATION_MESSAGE = "{\n" +
            "  \"type\": \"userRegistered\",\n" +
            "  \"message\": \"You are successfully registered\"\n" +
            "}";

    private final OkHttpClient httpClient = new OkHttpClient();

    @ExpectedScenarioState
    String restEntryPointHost;

    @ExpectedScenarioState
    int restEntryPointPort;

    @ExpectedScenarioState
    String currentUserLogin;

    @ExpectedScenarioState
    Map<String, UserInfo> currentUsers;

    @ProvidedScenarioState
    int responseHttpStatusCode;

    @ProvidedScenarioState
    String responseHttpStatusBody;

    @ProvidedScenarioState
    boolean receiveWebSocketWelcome = false;

    @ProvidedScenarioState
    List<BrickConfigDto> brickAvailable = new ArrayList<>();

    public SELF try_to_access_to_get_url_$(@Quoted String url) {
        return try_to_access_to_call_$_url_$("GET", url);
    }


    private SELF try_to_access_to_call_$_url_$(@Quoted String methodName, @Quoted String url) {

        Request.Builder builder = new Request.Builder().get().url(getBaseUrl() + url);
        if (currentUserLogin != null) {
            UserInfo requesterUserInfo = currentUsers.get(currentUserLogin);
            builder = HttpUserSupport.addBasicAuthentification(requesterUserInfo, builder);
        }
        Request request = builder.build();
        Response response = null;
        try {
            response = httpClient.newCall(request).execute();
            responseHttpStatusCode = response.code();
            responseHttpStatusBody = response.body().string();
            response.body().close();
        } catch (IOException e) {
            fail(e.getMessage());
        } finally {
            if (response != null) {
                IOUtils.closeQuietly(response.body());
            }
        }
        return self();
    }

/*
    private void connectToWebSocket(UserInfo requesterUserInfo, boolean expectSuccess) {

        try {

            final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();

            ClientManager client = ClientManager.createClient();
            if (requesterUserInfo != null) {
                client.getProperties().put(ClientProperties.CREDENTIALS, new Credentials(requesterUserInfo.getUsername(), requesterUserInfo.getPassword()));

            }

            String uriStr = "ws://" + restEntryPointHost + ":" + restEntryPointPort + "/api/v1/event";
            CountDownLatch messageLatch = new CountDownLatch(1);
            Session session = client.connectToServer(new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig config) {

                    session.addMessageHandler(new MessageHandler.Whole<String>() {
                        @Override
                        public void onMessage(String messsage) {
                            GsonBuilder builder = new GsonBuilder();
                            builder.registerTypeAdapter(BrickEventStateWebSocketMessage.class, new WebSocketMessageGsonAdapter());
                            Gson gson = builder.create();
                            BrickEventStateWebSocketMessage response = gson.fromJson(messsage, BrickEventStateWebSocketMessage.class);
                            LOGGER.info("Receive WebSocket mesage : {}", response);
                            if ("user".equals(response.getEntity())
                                    && "authentication".equals(response.getAction())
                                    && response.getData().has("message") && ((JsonObject) response.getData()).getAsJsonPrimitive("message").getAsString().equals("success")
                                    ) {
                                receiveWebSocketWelcome = true;
                                messageLatch.countDown();
                            } else {
                                receiveWebSocketWelcome = false;
                            }
                        }


                    });
                    if (requesterUserInfo != null) {
                        try {
                            String aggregateCredentials = String.format("%s:%s", requesterUserInfo.getUsername(), requesterUserInfo.getPassword());
                            String encodedCredentials = Base64.getEncoder().encodeToString(aggregateCredentials.getBytes());
                            session.getBasicRemote().sendText("{\n" +
                                    "  \"entity\": \"user\",\n" +
                                    "  \"action\": \"authentication\",\n" +
                                    "  \"data\": {\n" +
                                    "    \"authorization\": \"Basic " + encodedCredentials + "\"\n" +
                                    "  }\n" +
                                    "}");
                        } catch (IOException e) {
                            fail(e.getMessage());
                        }
                    }
                }

            }, cec, new URI(uriStr));
            messageLatch.await(10, TimeUnit.SECONDS);
            session.close();
        } catch (Exception e) {
            if (expectSuccess) {
                fail(e.getMessage());
            } else {
                receiveWebSocketWelcome = false;
            }
        }
    }
*/

    public SELF try_to_access_to_list_of_brick_available() {
        UserInfo currentUser = currentUsers.get(currentUserLogin);
        OkHttpClient httpClient = new OkHttpClient();
        Request.Builder builder = new Request.Builder().url(getBaseUrl() + "/api/v1/brick").get();
        builder = HttpUserSupport.addBasicAuthentification(currentUser, builder);
        Response response = null;

        try {
            response = httpClient.newCall(builder.build()).execute();
            Gson gson = new GsonBuilder().create();
            JsonParser parser = new JsonParser();
            String payload = response.body().string();
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(payload);
            }
            JsonArray root = (JsonArray) parser.parse(payload);
            for (JsonElement el : root) {
                brickAvailable.add(gson.fromJson(el, BrickConfigDto.class));
            }
        } catch (IOException e) {
            fail(e.getMessage());
        } finally {
            if (response != null) {
                IOUtils.closeQuietly(response.body());
            }
        }

        return self();
    }


    private String getBaseUrl() {
        return "http://" + restEntryPointHost + ":" + restEntryPointPort;
    }

}
