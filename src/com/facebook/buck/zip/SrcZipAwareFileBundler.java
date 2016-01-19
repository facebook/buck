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

package com.facebook.buck.zip;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class SrcZipAwareFileBundler {

  private final JavaPackageFinder javaPackageFinder;

  public SrcZipAwareFileBundler(JavaPackageFinder javaPackageFinder) {
    this.javaPackageFinder = javaPackageFinder;
  }

  private Path getRelativeFilePath(
      Path absoluteFilePath,
      Map<Path, Path> relativePathMap) {
    Path pathRelativeToBaseDir;

    pathRelativeToBaseDir = this.javaPackageFinder.findJavaPackageFolder(absoluteFilePath)
        .resolve(absoluteFilePath.getFileName());

    if (relativePathMap.containsKey(pathRelativeToBaseDir)) {
      throw new HumanReadableException("The file '%s' appears twice in the hierarchy",
          pathRelativeToBaseDir.getFileName());
    }
    return pathRelativeToBaseDir;
  }

  private ImmutableMap<Path, Path> createRelativeMap(
      ProjectFilesystem filesystem,
      final SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> toCopy) {
    Map<Path, Path> relativePathMap = new HashMap<>();

    for (SourcePath sourcePath : toCopy) {
      Path absoluteBasePath = resolver.getAbsolutePath(sourcePath);
      try {
        if (Files.isDirectory(absoluteBasePath)) {
          ImmutableSet<Path> files = filesystem.getFilesUnderPath(
              absoluteBasePath);

          for (Path absoluteFilePath : files) {
            Path pathRelativeToBaseDir = getRelativeFilePath(
                absoluteFilePath,
                relativePathMap);

            relativePathMap.put(pathRelativeToBaseDir, absoluteFilePath);
          }
        } else {
          Path pathRelativeToBaseDir = getRelativeFilePath(
              absoluteBasePath,
              relativePathMap);
          relativePathMap.put(pathRelativeToBaseDir, absoluteBasePath);
        }
      } catch (IOException e) {
        throw new RuntimeException(
            String.format("Couldn't read directory [%s].", absoluteBasePath.toString()),
            e);
      }
    }

    return ImmutableMap.copyOf(relativePathMap);
  }

  public void copy(
      ProjectFilesystem filesystem,
      final SourcePathResolver resolver,
      ImmutableList.Builder<Step> steps,
      Path destinationDir,
      ImmutableSortedSet<SourcePath> toCopy) {

    Map<Path, Path> relativeMap = createRelativeMap(filesystem, resolver, toCopy);

    for (Path relativePath : relativeMap.keySet()) {
      Path destination = destinationDir.resolve(relativePath);
      Path absolutePath = Preconditions.checkNotNull(relativeMap.get(relativePath));

      if (relativePath.toString().endsWith(Javac.SRC_ZIP) ||
          relativePath.toString().endsWith(Javac.SRC_JAR)) {
        steps.add(new MkdirStep(filesystem, destination));
        steps.add(new UnzipStep(filesystem, absolutePath, destination));
        continue;
      }

      if (destination.getParent() != null) {
        steps.add(new MkdirStep(filesystem, destination.getParent()));
      }
      steps.add(CopyStep.forFile(filesystem, absolutePath, destination));
    }
  }
}
