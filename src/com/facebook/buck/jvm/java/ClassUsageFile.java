/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSetMultimap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

public final class ClassUsageFile {
  public static void writeFromTrackerData(
      ProjectFilesystem filesystem,
      Path absolutePath,
      ClassUsageTracker tracker,
      ObjectMapper mapper) {
    ImmutableSetMultimap<Path, Path> classUsageMap = tracker.getClassUsageMap();
    try {
      mapper.writeValue(absolutePath.toFile(), relativizeMap(classUsageMap, filesystem));
    } catch (IOException e) {
      throw new HumanReadableException(e, "Unable to write used classes file.");
    }
  }

  private static ImmutableSetMultimap<Path, Path> relativizeMap(
      ImmutableSetMultimap<Path, Path> classUsageMap,
      ProjectFilesystem filesystem) {
    final ImmutableSetMultimap.Builder<Path, Path> builder = ImmutableSetMultimap.builder();

    for (Map.Entry<Path, Collection<Path>> jarClassesEntry : classUsageMap.asMap().entrySet()) {
      Path jarAbsolutePath = jarClassesEntry.getKey();
      Path jarRelativePath = filesystem.getRelativizer().apply(jarAbsolutePath);

      // Don't include jars that are outside of the filesystem
      if (jarRelativePath.startsWith("..")) {
        continue;
      }

      builder.putAll(jarRelativePath, jarClassesEntry.getValue());
    }

    return builder.build();
  }

  private ClassUsageFile() {
  }
}
