/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Utilities for handling paths to java source files. */
public class JavaPaths {
  public static final String SRC_ZIP = ".src.zip";
  public static final String SRC_JAR = "-sources.jar";

  /**
   * Processes a list of java source files, extracting and SRC_ZIP or SRC_JAR to the working
   * directory and returns a list of all the resulting .java files.
   */
  static ImmutableList<Path> extractArchivesAndGetPaths(
      ProjectFilesystem projectFilesystem,
      ProjectFilesystemFactory projectFilesystemFactory,
      ImmutableSet<Path> javaSourceFilePaths,
      Path workingDirectory)
      throws InterruptedException, IOException {

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : javaSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(".java")) {
        sources.add(path);
      } else if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ImmutableList<Path> zipPaths =
            ArchiveFormat.ZIP
                .getUnarchiver()
                .extractArchive(
                    projectFilesystemFactory,
                    projectFilesystem.resolve(path),
                    projectFilesystem.resolve(workingDirectory),
                    ExistingFileMode.OVERWRITE);
        sources.addAll(
            zipPaths.stream().filter(input -> input.toString().endsWith(".java")).iterator());
      }
    }
    return sources.build();
  }

  /**
   * Traverses a list of java inputs return the list of all found files (for SRC_ZIP/SRC_JAR, it
   * returns the paths from within the archive).
   */
  static ImmutableList<Path> getExpandedSourcePaths(Iterable<Path> javaSourceFilePaths)
      throws IOException {
    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : javaSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
          Enumeration<? extends ZipEntry> entries = zipFile.entries();
          while (entries.hasMoreElements()) {
            sources.add(Paths.get(entries.nextElement().getName()));
          }
        }
      } else {
        sources.add(path);
      }
    }
    return sources.build();
  }
}
