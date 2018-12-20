/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.Javac.SRC_JAR;
import static com.facebook.buck.jvm.java.Javac.SRC_ZIP;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Interface for a kotlin compiler. */
public interface Kotlinc extends Tool {

  KotlincVersion getVersion();

  int buildWithClasspath(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<Path> kotlinHomeLibraries,
      ImmutableList<String> options,
      ImmutableSortedSet<Path> kotlinSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      ProjectFilesystem fileSystem)
      throws InterruptedException;

  String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> kotlinSourceFilePaths,
      Path pathToSrcsList);

  String getShortName();

  Path getAnnotationProcessorPath(SourcePathResolver sourcePathResolver);

  Path getStdlibPath(SourcePathResolver sourcePathResolver);

  ImmutableList<Path> getAdditionalClasspathEntries(SourcePathResolver sourcePathResolver);

  ImmutableList<Path> getHomeLibraries(SourcePathResolver sourcePathResolver);

  default ImmutableList<Path> getExpandedSourcePaths(
      ProjectFilesystem projectFilesystem,
      ProjectFilesystemFactory projectFilesystemFactory,
      ImmutableSet<Path> kotlinSourceFilePaths,
      Optional<Path> workingDirectory)
      throws InterruptedException, IOException {

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : kotlinSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(".kt")
          || pathString.endsWith(".kts")
          || pathString.endsWith(".java")) {
        sources.add(path);
      } else if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ImmutableList<Path> zipPaths =
            ArchiveFormat.ZIP
                .getUnarchiver()
                .extractArchive(
                    projectFilesystemFactory,
                    projectFilesystem.resolve(path),
                    projectFilesystem.resolve(workingDirectory.orElse(path)),
                    ExistingFileMode.OVERWRITE);
        sources.addAll(
            zipPaths
                .stream()
                .filter(
                    input ->
                        input.toString().endsWith(".kt")
                            || input.toString().endsWith(".kts")
                            || input.toString().endsWith(".java"))
                .iterator());
      }
    }
    return sources.build();
  }
}
