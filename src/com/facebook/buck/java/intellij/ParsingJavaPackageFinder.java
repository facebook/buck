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

package com.facebook.buck.java.intellij;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.JavaFileParser;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Finds the package for a given file by looking at its contents first.
 */
public class ParsingJavaPackageFinder implements JavaPackageFinder {

  private JavaFileParser javaFileParser;
  private ProjectFilesystem projectFilesystem;
  private JavaPackageFinder fallbackPackageFinder;
  private Map<Path, Path> pathToPackagePathCache;

  public ParsingJavaPackageFinder(
      JavaFileParser javaFileParser,
      ProjectFilesystem projectFilesystem,
      JavaPackageFinder fallbackPackageFinder) {
    this.javaFileParser = javaFileParser;
    this.projectFilesystem = projectFilesystem;
    this.fallbackPackageFinder = fallbackPackageFinder;
    this.pathToPackagePathCache = new HashMap<>();
  }

  @Override
  public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
    Optional<Path> packageFolder = cacheLookup(pathRelativeToProjectRoot);
    if (!packageFolder.isPresent()) {
      packageFolder = getPackageFromFile(pathRelativeToProjectRoot).transform(
          new Function<String, Path>() {
            @Override
            public Path apply(String input) {
              return findPackageFolderWithJavaPackage(input);
            }
          });
    }
    if (!packageFolder.isPresent()) {
      packageFolder = Optional.of(
          fallbackPackageFinder.findJavaPackageFolder(pathRelativeToProjectRoot));
    }
    cacheInsert(pathRelativeToProjectRoot, packageFolder.get());
    return packageFolder.get();
  }

  private void cacheInsert(Path pathRelativeToProjectRoot, Path packageFolder) {
    Path parentPath = pathRelativeToProjectRoot.getParent();
    if (parentPath.endsWith(Preconditions.checkNotNull(packageFolder))) {
      Path packagePath = packageFolder;
      for (int i = 0; i < packageFolder.getNameCount(); ++i) {
        pathToPackagePathCache.put(parentPath, packagePath);
        parentPath = MorePaths.getParentOrEmpty(parentPath);
        packagePath = MorePaths.getParentOrEmpty(packagePath);
      }
    }
  }

  private Optional<Path> cacheLookup(Path pathRelativeToProjectRoot) {
    Path path = pathRelativeToProjectRoot.getParent();
    while (path != null) {
      Path prefix = pathToPackagePathCache.get(path);
      if (prefix != null) {
        Path suffix = path.relativize(pathRelativeToProjectRoot.getParent());
        return Optional.of(prefix.resolve(suffix));
      }
      path = path.getParent();
    }

    return Optional.absent();
  }

  private Optional<String> getPackageFromFile(Path pathRelativeToProjectRoot) {
    Optional<String> contents = projectFilesystem.readFileIfItExists(pathRelativeToProjectRoot);
    return Optionals.bind(
        contents, new Function<String, Optional<String>>() {
          @Override
          public Optional<String> apply(String input) {
            return javaFileParser.getPackageNameFromSource(input);
          }
        });
  }

  @Override
  public String findJavaPackage(Path pathRelativeToProjectRoot) {
    Path folder = findJavaPackageFolder(pathRelativeToProjectRoot);
    return DefaultJavaPackageFinder.findJavaPackageWithPackageFolder(folder);
  }

  @Override
  public String findJavaPackage(BuildTarget buildTarget) {
    return findJavaPackage(buildTarget.getBasePath().resolve("removed"));
  }

  private static Path findPackageFolderWithJavaPackage(String javaPackage) {
    return Paths.get(javaPackage.replace('.', File.separatorChar));
  }
}
