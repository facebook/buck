/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/**
 * Provides utility methods for reading dependency file entries.
 */
class DefaultClassUsageFileReader {
  private static final ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();

  /**
   * Utility code, not instantiable
   */
  private DefaultClassUsageFileReader() {}

  private static ImmutableMap<Path, SourcePath> buildJarToAbiJarMap(
      ImmutableSortedSet<BuildRule> deps) {
    ImmutableMap.Builder<Path, SourcePath> jarAbsolutePathToAbiJarSourcePathBuilder =
        ImmutableMap.builder();

    for (BuildRule dep : deps) {
      if (!(dep instanceof HasJavaAbi)) {
        continue;
      }

      HasJavaAbi depWithJavaAbi = (HasJavaAbi) dep;
      Optional<BuildTarget> depAbiJar = depWithJavaAbi.getAbiJar();
      if (!depAbiJar.isPresent()) {
        continue;
      }

      Path jarAbsolutePath = dep.getProjectFilesystem().resolve(dep.getPathToOutput());

      jarAbsolutePathToAbiJarSourcePathBuilder.put(
          jarAbsolutePath,
          new BuildTargetSourcePath(depAbiJar.get()));
    }

    return jarAbsolutePathToAbiJarSourcePathBuilder.build();
  }

  private static ImmutableMap<String, ImmutableList<String>> loadClassUsageMap(
      Path mapFilePath) throws IOException {
    return objectMapper.readValue(
        mapFilePath.toFile(),
        new TypeReference<ImmutableMap<String, ImmutableList<String>>>() {
        });
  }

  public static ImmutableList<SourcePath> loadFromFile(
      ProjectFilesystem projectFilesystem,
      Path classUsageFilePath,
      ImmutableSortedSet<BuildRule> deps) {
    final ImmutableMap<Path, SourcePath> jarAbsolutePathToAbiJarSourcePath =
        buildJarToAbiJarMap(deps);
    final ImmutableList.Builder<SourcePath> builder = ImmutableList.builder();
    try {
      final ImmutableSet<Map.Entry<String, ImmutableList<String>>> classUsageEntries =
          loadClassUsageMap(classUsageFilePath).entrySet();
      for (Map.Entry<String, ImmutableList<String>> jarUsedClassesEntry : classUsageEntries) {
        Path jarAbsolutePath = projectFilesystem.resolve(Paths.get(jarUsedClassesEntry.getKey()));
        SourcePath abiJarSourcePath = jarAbsolutePathToAbiJarSourcePath.get(jarAbsolutePath);
        if (abiJarSourcePath == null) {
          // This indicates a dependency that wasn't among the deps of the rule; i.e.,
          // it came from the build environment (JDK, Android SDK, etc.)
          continue;
        }

        ImmutableList<String> classAbsolutePaths = jarUsedClassesEntry.getValue();
        for (String classAbsolutePath : classAbsolutePaths) {
          builder.add(
              new ArchiveMemberSourcePath(abiJarSourcePath, Paths.get(classAbsolutePath)));
        }
      }
    } catch (IOException e) {
      throw new HumanReadableException(e, "Failed to load class usage files from %s:\n%s",
          classUsageFilePath, e.getLocalizedMessage());
    }
    return builder.build();
  }
}
