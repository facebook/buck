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

package com.facebook.buck.maven;

import static org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_FAIL;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;

import java.net.URL;

public class AetherUtil {

  public static final String CLASSIFIER_SOURCES = "sources";
  public static final String CLASSIFIER_JAVADOC = "javadoc";

  private AetherUtil() {
  }

  public static RemoteRepository toRemoteRepository(URL remoteRepositoryUrl) {
    return toRemoteRepository(remoteRepositoryUrl.toString());
  }

  public static RemoteRepository toRemoteRepository(String remoteRepositoryUrl) {
    return new RemoteRepository.Builder(remoteRepositoryUrl, "default", remoteRepositoryUrl)
        .setPolicy(new RepositoryPolicy(true, null, CHECKSUM_POLICY_FAIL))
        .build();
  }

  public static ServiceLocator initServiceLocator() {
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.setErrorHandler(
        new DefaultServiceLocator.ErrorHandler() {
          @Override
          public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
            throw new RuntimeException(
                String.format("Failed to initialize service %s, implemented by %s: %s",
                    type.getName(),
                    impl.getName(),
                    exception.getMessage()),
                    exception);
          }
        });
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
    // Use a no-op logger. Leaving this out would introduce a runtime dependency on log4j
    locator.addService(ILoggerFactory.class, NOPLoggerFactory.class);
    // Also requires log4j
//    locator.addService(ILoggerFactory.class, Log4jLoggerFactory.class);
    return locator;
  }

  /**
   * Transforms maven coordinates, adding the specified classifier
   */
  public static String addClassifier(String mavenCoords, String classifier) {
    DefaultArtifact base = new DefaultArtifact(mavenCoords);
    return new DefaultArtifact(
        base.getGroupId(),
        base.getArtifactId(),
        classifier,
        base.getExtension(),
        base.getVersion()).toString();
  }
}
