/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.maven.aether;

import static org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_FAIL;

import java.net.URL;
import java.util.Optional;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.slf4j.ILoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;

public class AetherUtil {

  public static final String CLASSIFIER_SOURCES = "sources";

  private AetherUtil() {}

  public static RemoteRepository toRemoteRepository(
      URL repoUrl, Optional<String> username, Optional<String> password) {
    return toRemoteRepository(repoUrl.toString(), username, password);
  }

  public static RemoteRepository toRemoteRepository(
      String repoUrl, Optional<String> username, Optional<String> password) {
    RemoteRepository.Builder repo =
        new RemoteRepository.Builder(null, "default", repoUrl)
            .setPolicy(new RepositoryPolicy(true, null, CHECKSUM_POLICY_FAIL));

    if (username.isPresent() && password.isPresent()) {
      Authentication authentication =
          new AuthenticationBuilder()
              .addUsername(username.get())
              .addPassword(password.get())
              .build();
      repo.setAuthentication(authentication);
    }

    return repo.build();
  }

  public static RemoteRepository toRemoteRepository(Repository repo) {
    RemoteRepository.Builder builder =
        new RemoteRepository.Builder(repo.getUrl(), "default", repo.getUrl())
            .setPolicy(new RepositoryPolicy(true, null, CHECKSUM_POLICY_FAIL));

    if (repo.user != null && repo.password != null) {
      builder.setAuthentication(
          new AuthenticationBuilder().addUsername(repo.user).addPassword(repo.password).build());
    }

    return builder.build();
  }

  public static ServiceLocator initServiceLocator() {
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.setErrorHandler(
        new DefaultServiceLocator.ErrorHandler() {
          @Override
          public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
            throw new RuntimeException(
                String.format(
                    "Failed to initialize service %s, implemented by %s: %s",
                    type.getName(), impl.getName(), exception.getMessage()),
                exception);
          }
        });
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    // Use a no-op logger. Leaving this out would introduce a runtime dependency on log4j
    locator.addService(ILoggerFactory.class, NOPLoggerFactory.class);
    // Also requires log4j
    //    locator.addService(ILoggerFactory.class, Log4jLoggerFactory.class);
    return locator;
  }

  /** Transforms maven coordinates, adding the specified classifier */
  public static String addClassifier(String mavenCoords, String classifier) {
    DefaultArtifact base = new DefaultArtifact(mavenCoords);
    return new DefaultArtifact(
            base.getGroupId(),
            base.getArtifactId(),
            classifier,
            base.getExtension(),
            base.getVersion())
        .toString();
  }
}
