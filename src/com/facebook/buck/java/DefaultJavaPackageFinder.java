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

package com.facebook.buck.java;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;

public class DefaultJavaPackageFinder implements JavaPackageFinder {

  /**
   * Each element in this set is a path prefix from the root of the repository.
   * <p>
   * Elements in this set are ordered opposite to their natural order such that if one element is a
   * prefix of another element in the Set, the longer String will appear first. This makes it
   * possible to iterate over the elements in the set in order, comparing to a test element, such
   * that the longest matching prefix matches the test element.
   * <p>
   * Every element in this set ends with a slash.
   */
  private final ImmutableSortedSet<String> pathsFromRoot;

  private final ImmutableSet<String> pathElements;

  private DefaultJavaPackageFinder(
      ImmutableSortedSet<String> pathsFromRoot,
      ImmutableSet<String> pathElements) {
    this.pathsFromRoot = pathsFromRoot;
    this.pathElements = pathElements;
  }

  @Override
  public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
    for (String pathFromRoot : pathsFromRoot) {
      if (pathRelativeToProjectRoot.startsWith(pathFromRoot)) {
        return MorePaths.getParentOrEmpty(
            MorePaths.relativize(Paths.get(pathFromRoot), pathRelativeToProjectRoot));
      }
    }

    Path directory = pathRelativeToProjectRoot.getParent();
    Deque<String> parts = Lists.newLinkedList();
    while (directory != null && !pathElements.contains(directory.getFileName().toString())) {
      parts.addFirst(directory.getFileName().toString());
      directory = directory.getParent();
    }

    if (!parts.isEmpty()) {
      return Paths.get(Joiner.on(File.separatorChar).join(parts));
    } else {
      return Paths.get("");
    }
  }

  public ImmutableSortedSet<String> getPathsFromRoot() {
    return pathsFromRoot;
  }

  public ImmutableSet<String> getPathElements() {
    return pathElements;
  }

  /**
   * @param pathPatterns elements that start with a slash must be prefix patterns; all other
   *     elements indicate individual directory names (and therefore cannot contain slashes).
   */
  public static DefaultJavaPackageFinder createDefaultJavaPackageFinder(
      Iterable<String> pathPatterns) {
    ImmutableSortedSet.Builder<String> pathsFromRoot = ImmutableSortedSet.reverseOrder();
    ImmutableSet.Builder<String> pathElements = ImmutableSet.builder();
    for (String pattern : pathPatterns) {
      if (pattern.charAt(0) == '/') {
        // Strip the leading slash.
        pattern = pattern.substring(1);

        // Ensure there is a trailing slash, unless it is an empty string.
        if (!pattern.isEmpty() && !pattern.endsWith("/")) {
          pattern = pattern + "/";
        }
        pathsFromRoot.add(pattern);
      } else {
        if (pattern.contains("/")) {
          throw new HumanReadableException(
              "Path pattern that does not start with a slash cannot contain a slash: %s", pattern);
        }
        pathElements.add(pattern);
      }
    }
    return new DefaultJavaPackageFinder(pathsFromRoot.build(), pathElements.build());
  }

  @Override
  public String findJavaPackage(Path pathRelativeToProjectRoot) {
    Path folder = findJavaPackageFolder(pathRelativeToProjectRoot);
    return findJavaPackageWithPackageFolder(folder);
  }

  @Override
  public String findJavaPackage(BuildTarget buildTarget) {
    return findJavaPackage(buildTarget.getBasePath().resolve("removed"));
  }

  public static String findJavaPackageWithPackageFolder(Path packageFolder) {
    return packageFolder.toString().replace(File.separatorChar, '.');
  }
}
