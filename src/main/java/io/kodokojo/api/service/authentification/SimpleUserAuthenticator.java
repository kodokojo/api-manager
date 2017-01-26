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
package io.kodokojo.api.service.authentification;

import io.kodokojo.commons.model.User;
import io.kodokojo.api.endpoint.UserAuthenticator;
import io.kodokojo.commons.service.repository.UserFetcher;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

public class SimpleUserAuthenticator implements UserAuthenticator<SimpleCredential> {

    private final UserFetcher userFetcher;

    @Inject
    public SimpleUserAuthenticator(UserFetcher userFetcher) {
        requireNonNull(userFetcher, "userFetcher must be defined.");
        this.userFetcher = userFetcher;
    }

    @Override
    public User authenticate(SimpleCredential credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must be defined.");
        }
        User user = userFetcher.getUserByUsername(credentials.getUsername());
        return (user != null && user.getPassword().equals(credentials.getPassword())) ? user : null;
    }

}
