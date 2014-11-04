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

package com.facebook.buck.java;

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

public class SrcRootsFinder {

  private final ProjectFilesystem projectFilesystem;

  public SrcRootsFinder(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  /**
   * Source roots for Java code can be specified in one of two ways. They can be given as paths
   * relative to the root of the project (e.g. "/java/"), or they can be given as slash-less
   * directory names that can live anywhere in the project (e.g. "src"). Convert these src_root
   * specifier strings into actual paths, by walking the project and collecting all the matches.
   * @param pathPatterns the strings obtained from the src_root field of the .buckconfig
   * @return a set of paths to all the actual source root directories in the project
   */
  public ImmutableSet<Path> getAllSrcRootPaths(Iterable<String> pathPatterns) throws IOException {
    ImmutableSet.Builder<Path> srcRootPaths = ImmutableSet.builder();
    DefaultJavaPackageFinder finder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(pathPatterns);
    for (String pathFromRoot : finder.getPathsFromRoot()) {
      srcRootPaths.add(Paths.get(pathFromRoot));
    }
    srcRootPaths.addAll(findAllMatchingPaths(finder.getPathElements()));
    return srcRootPaths.build();
  }

  private ImmutableSet<Path> findAllMatchingPaths(final Set<String> pathElements)
      throws IOException {
    final ImmutableSet.Builder<Path> matchingPaths = ImmutableSet.builder();
    projectFilesystem.walkRelativeFileTree(
        Paths.get(""),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir,
              BasicFileAttributes attrs)
              throws IOException {
            if (pathElements.contains(dir.getFileName().toString())) {
              matchingPaths.add(dir);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return matchingPaths.build();
  }
}
