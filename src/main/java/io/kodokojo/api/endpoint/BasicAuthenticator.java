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

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Filter;
import spark.Request;
import spark.Response;

import java.util.Base64;

import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BasicAuthenticator implements Filter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BasicAuthenticator.class);

    public static final String AUTHORIZATION_HEADER_NAME = "Authorization";

    private String username;

    private String password;

    @Override
    public void handle(Request request, Response response) throws Exception {
        String authorization = request.headers(AUTHORIZATION_HEADER_NAME);
            if (isNotBlank(authorization)) {
            String[] split = explodeUserAndPassword(authorization);
            if (split != null && split.length == 2) {
                username = split[0];
                password = split[1];
            }

        }  else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Basic Authorization header not found.");
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("List of Header of current request {}", StringUtils.join(request.headers(), ","));
                LOGGER.trace("List of attribute : {}", StringUtils.join(request.attributes(), ","));
                LOGGER.trace("List of params : {}", StringUtils.join(request.queryParams(), ","));
            }
        }
    }

    public boolean isProvideCredentials() {
        return isNotBlank(username) && isNotBlank(password);
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public static String[] explodeUserAndPassword(String headerValue) {
        if (StringUtils.isNotBlank(headerValue) && headerValue.startsWith("Basic ")) {
            String encoded = headerValue.substring("Basic ".length());
            String decoded = new String(Base64.getDecoder().decode(encoded));
            String[] split = decoded.split(":");
            return split;
        }
        return null;
    }
}
