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
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Finds the package for a given file by looking at its contents first.
 */
public abstract class ParsingJavaPackageFinder {

  /**
   * Creates a hybrid {@link JavaPackageFinder} which will resolve packages for the selected paths
   * based on parsing the source files and use the fallbackPackageFinder for everything else.
   *
   * @param javaFileParser parser to read Java sources with.
   * @param projectFilesystem filesystem.
   * @param filesToParse set of files to parse.
   * @param fallbackPackageFinder package finder to use when the package can't be inferred from
   *                              source.
   * @return the described PackageFinder.
   */
  public static JavaPackageFinder preparse(
      final JavaFileParser javaFileParser,
      ProjectFilesystem projectFilesystem,
      ImmutableSet<Path> filesToParse,
      JavaPackageFinder fallbackPackageFinder) {
    PackagePathCache packagePathCache = new PackagePathCache();
    for (Path path : FluentIterable.from(filesToParse).toSortedSet(new PathComponentCountOrder())) {
      Optional<String> packageNameFromSource = Optionals.bind(
          projectFilesystem.readFileIfItExists(path),
          new Function<String, Optional<String>>() {
            @Override
            public Optional<String> apply(String input) {
              return javaFileParser.getPackageNameFromSource(input);
            }
          });
      if (packageNameFromSource.isPresent()) {
        Path javaPackagePath = findPackageFolderWithJavaPackage(packageNameFromSource.get());
        packagePathCache.insert(path, javaPackagePath);
      }
    }
    return new CacheBasedPackageFinder(fallbackPackageFinder, packagePathCache);
  }

  private static Path findPackageFolderWithJavaPackage(String javaPackage) {
    return Paths.get(javaPackage.replace('.', File.separatorChar));
  }

  public static class PackagePathCache {
    private Map<Path, Path> cache;

    public PackagePathCache() {
      this.cache = new HashMap<>();
    }

    public void insert(Path pathRelativeToProjectRoot, Path packageFolder) {
      Path parentPath = pathRelativeToProjectRoot.getParent();
      cache.put(parentPath, packageFolder);

      if (parentPath.endsWith(Preconditions.checkNotNull(packageFolder))) {
        Path packagePath = packageFolder;
        for (int i = 0; i <= packageFolder.getNameCount(); ++i) {
          cache.put(parentPath, packagePath);
          parentPath = MorePaths.getParentOrEmpty(parentPath);
          packagePath = MorePaths.getParentOrEmpty(packagePath);
        }
      }
    }

    public Optional<Path> lookup(Path pathRelativeToProjectRoot) {
      Path path = pathRelativeToProjectRoot.getParent();
      while (path != null) {
        Path prefix = cache.get(path);
        if (prefix != null) {
          Path suffix = path.relativize(pathRelativeToProjectRoot.getParent());
          return Optional.of(prefix.resolve(suffix));
        }
        path = path.getParent();
      }

      return Optional.absent();
    }
  }

  private static class CacheBasedPackageFinder implements JavaPackageFinder {
    private JavaPackageFinder fallbackPackageFinder;
    private PackagePathCache packagePathCache;

    public CacheBasedPackageFinder(
        JavaPackageFinder fallbackPackageFinder,
        PackagePathCache packagePathCache) {
      this.fallbackPackageFinder = fallbackPackageFinder;
      this.packagePathCache = packagePathCache;
    }

    @Override
    public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
      Optional<Path> packageFolder = packagePathCache.lookup(pathRelativeToProjectRoot);
      if (!packageFolder.isPresent()) {
        packageFolder = Optional.of(
            fallbackPackageFinder.findJavaPackageFolder(pathRelativeToProjectRoot));
      }
      return packageFolder.get();
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
  }

  public static class PathComponentCountOrder implements Comparator<Path> {
    @Override
    public int compare(Path o1, Path o2) {
      int lengthCompare = Integer.compare(o2.getNameCount(), o1.getNameCount());
      if (lengthCompare == 0) {
        return o2.compareTo(o1);
      }
      return lengthCompare;
    }
  }
}
