/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.util;

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public class DefaultPropertyFinder implements PropertyFinder {
  private static final Path LOCAL_PROPERTIES_PATH = Paths.get("local.properties");

  private final ProjectFilesystem projectFilesystem;
  private final ImmutableMap<String, String> environment;

  public DefaultPropertyFinder(
      ProjectFilesystem projectFilesystem,
      ImmutableMap<String, String> environment) {
    this.projectFilesystem = projectFilesystem;
    this.environment = environment;
  }

  /**
   * @param propertyName The name of the property to look for in local.properties.
   * @param environmentVariables The name of the environment variables to try.
   * @return If present, the value is confirmed to be a directory.
   */
  @Override
  public Optional<Path> findDirectoryByPropertiesThenEnvironmentVariable(
      String propertyName,
      String... environmentVariables) {
    Optional<Properties> localProperties;
    try {
      localProperties = Optional.of(projectFilesystem.readPropertiesFile(LOCAL_PROPERTIES_PATH));
    } catch (FileNotFoundException e) {
      localProperties = Optional.absent();
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Couldn't read properties file [%s].", LOCAL_PROPERTIES_PATH),
          e);
    }

    return
        findDirectoryByPropertiesThenEnvironmentVariable(
            localProperties,
            new HostFilesystem(),
            environment,
            propertyName,
            environmentVariables);
  }

  @VisibleForTesting
  static Optional<Path> findDirectoryByPropertiesThenEnvironmentVariable(
      Optional<Properties> localProperties,
      HostFilesystem hostFilesystem,
      Map<String, String> systemEnvironment,
      String propertyName,
      String... environmentVariables) {
    // First, try to find a value in local.properties using the specified propertyName.
    Path dirPath = null;
    if (localProperties.isPresent()) {
      String propertyValue = localProperties.get().getProperty(propertyName);
      if (propertyValue != null) {
        dirPath = Paths.get(propertyValue);
      }
    }

    String dirPathEnvironmentVariable = null;

    // If dirPath is not set, try each of the environment variables, in order, to find it.
    for (String environmentVariable : environmentVariables) {
      if (dirPath == null) {
        String environmentVariableValue = systemEnvironment.get(environmentVariable);
        if (environmentVariableValue != null) {
          dirPath = Paths.get(environmentVariableValue);
          dirPathEnvironmentVariable = environmentVariable;
        }
      } else {
        break;
      }
    }

    // If a dirPath was found, verify that it maps to a directory before returning it.
    if (dirPath == null) {
      return Optional.absent();
    } else {
      if (!hostFilesystem.isDirectory(dirPath)) {
        String message;
        if (dirPathEnvironmentVariable != null) {
          message = String.format(
              "Environment variable %s points to invalid path [%s].",
              dirPathEnvironmentVariable,
              dirPath);
        } else {
          message = String.format(
              "Properties file %s contains invalid path [%s] for key %s.",
              LOCAL_PROPERTIES_PATH,
              dirPath,
              propertyName);
        }
        throw new RuntimeException(message);
      }
      return Optional.of(dirPath);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof DefaultPropertyFinder)) {
      return false;
    }

    DefaultPropertyFinder that = (DefaultPropertyFinder) other;

    return Objects.equals(projectFilesystem, that.projectFilesystem);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectFilesystem);
  }
}
