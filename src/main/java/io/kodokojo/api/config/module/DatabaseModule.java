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
package io.kodokojo.api.config.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.kodokojo.commons.config.RedisConfig;
import io.kodokojo.commons.service.lifecycle.ApplicationLifeCycleManager;
import io.kodokojo.commons.service.redis.RedisEntityStore;
import io.kodokojo.commons.service.redis.RedisProjectStore;
import io.kodokojo.commons.service.redis.RedisUserRepository;
import io.kodokojo.commons.service.repository.EntityRepository;
import io.kodokojo.commons.service.repository.ProjectRepository;
import io.kodokojo.commons.service.repository.Repository;
import io.kodokojo.commons.service.repository.UserRepository;
import io.kodokojo.commons.service.repository.store.EntityStore;
import io.kodokojo.commons.service.repository.store.ProjectStore;

import javax.crypto.SecretKey;
import javax.inject.Named;

public class DatabaseModule extends AbstractModule {

    @Override
    protected void configure() {
        /*
        Multibinder<UserRepository> multibinder = Multibinder.newSetBinder(binder(), UserRepository.class);
        multibinder.addBinding().toProvider(RedisUserManagerProvider.class);
        */
    }

    @Provides
    @Singleton
    Repository provideRepository(EntityStore entityStore, ProjectStore projectStore, @Named("securityKey")SecretKey secretKey, RedisConfig redisConfig, ApplicationLifeCycleManager applicationLifeCycleManager) {
        RedisUserRepository redisUserManager = new RedisUserRepository(secretKey, redisConfig.host(), redisConfig.port(), redisConfig.password());
        applicationLifeCycleManager.addService(redisUserManager);
        return new Repository(redisUserManager,redisUserManager, entityStore, projectStore);
    }

    @Provides
    @Singleton
    UserRepository provideUserRepository(Repository repository) {
        return repository;
    }

    @Provides
    @Singleton
    EntityRepository provideEntityRepository(Repository repository) {
        return repository;
    }

    @Provides
    @Singleton
    ProjectRepository provideProjectRepository(Repository repository) {
        return repository;
    }


    @Provides
    @Singleton
    EntityStore provideEntityStore(@Named("securityKey") SecretKey key, RedisConfig redisConfig, ApplicationLifeCycleManager applicationLifeCycleManager) {
        RedisEntityStore entityStore = new RedisEntityStore(key, redisConfig.host(), redisConfig.port(), redisConfig.password());
        applicationLifeCycleManager.addService(entityStore);
        return entityStore;
    }

    @Provides
    @Singleton
    ProjectStore provideProjectStore(@Named("securityKey") SecretKey key, RedisConfig redisConfig, ApplicationLifeCycleManager applicationLifeCycleManager) {
        RedisProjectStore redisProjectStore = new RedisProjectStore(key, redisConfig.host(), redisConfig.port(), redisConfig.password());
        applicationLifeCycleManager.addService(redisProjectStore);
        return redisProjectStore;
    }

}
