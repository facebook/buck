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
import com.facebook.buck.util.ObjectMappers;
import com.google.common.collect.ImmutableSetMultimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import javax.tools.StandardJavaFileManager;

public final class DefaultClassUsageFileWriter implements ClassUsageFileWriter {

  private final Path relativePath;
  private final ClassUsageTracker tracker = new ClassUsageTracker();

  public DefaultClassUsageFileWriter(Path relativePath) {
    this.relativePath = relativePath;
  }

  public Path getRelativePath() {
    return relativePath;
  }

  @Override
  public StandardJavaFileManager wrapFileManager(StandardJavaFileManager inner) {
    return tracker.wrapFileManager(inner);
  }

  @Override
  public void writeFile(ProjectFilesystem filesystem) {
    ImmutableSetMultimap<Path, Path> classUsageMap = tracker.getClassUsageMap();
    try {
      ObjectMappers.WRITER.writeValue(
          filesystem.resolve(relativePath).toFile(), relativizeMap(classUsageMap, filesystem));
    } catch (IOException e) {
      throw new HumanReadableException(e, "Unable to write used classes file.");
    }
  }

  private static ImmutableSetMultimap<Path, Path> relativizeMap(
      ImmutableSetMultimap<Path, Path> classUsageMap, ProjectFilesystem filesystem) {
    final ImmutableSetMultimap.Builder<Path, Path> builder = ImmutableSetMultimap.builder();

    // Ensure deterministic ordering.
    builder.orderKeysBy(Comparator.naturalOrder());
    builder.orderValuesBy(Comparator.naturalOrder());

    for (Map.Entry<Path, Collection<Path>> jarClassesEntry : classUsageMap.asMap().entrySet()) {
      Path jarAbsolutePath = jarClassesEntry.getKey();
      Optional<Path> jarRelativePath = filesystem.getPathRelativeToProjectRoot(jarAbsolutePath);

      // Don't include jars that are outside of the filesystem
      if (!jarRelativePath.isPresent()) {
        // Paths outside the project would make these class usage files problematic for caching.
        // Fortunately, such paths are also not interesting for the main use cases that these files
        // address, namely understanding the dependencies one java rule has on others. Jar files
        // outside the project are coming from a build tool (e.g. JDK or Android SDK).
        continue;
      }

      builder.putAll(jarRelativePath.get(), jarClassesEntry.getValue());
    }

    return builder.build();
  }
}
