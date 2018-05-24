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

package com.facebook.buck.ide.intellij.lang.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.Optionals;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/** Finds the package for a given file by looking at its contents first. */
public abstract class ParsingJavaPackageFinder {
  private static final Logger logger = Logger.get(ParsingJavaPackageFinder.class);

  private static final Pattern END_OF_PRELUDE =
      Pattern.compile("^\\s*(import|public|protected|class|interface|enum)");
  /**
   * Creates a hybrid {@link JavaPackageFinder} which will resolve packages for the selected paths
   * based on parsing the source files and use the fallbackPackageFinder for everything else.
   *
   * @param javaFileParser parser to read Java sources with.
   * @param projectFilesystem filesystem.
   * @param filesToParse set of files to parse.
   * @param fallbackPackageFinder package finder to use when the package can't be inferred from
   *     source.
   * @return the described PackageFinder.
   */
  public static JavaPackageFinder preparse(
      JavaFileParser javaFileParser,
      ProjectFilesystem projectFilesystem,
      ImmutableSet<Path> filesToParse,
      JavaPackageFinder fallbackPackageFinder) {
    JavaPackagePathCache packagePathCache = new JavaPackagePathCache();
    PackagePathResolver packagePathResolver =
        new PackagePathResolver(javaFileParser, projectFilesystem);
    for (Path path : ImmutableSortedSet.copyOf(new PathComponentCountOrder(), filesToParse)) {
      packagePathResolver
          .getPackagePathFromSource(path)
          .ifPresent(javaPackagePath -> packagePathCache.insert(path, javaPackagePath));
    }
    return new CacheBasedPackageFinder(fallbackPackageFinder, packagePathCache);
  }

  /**
   * Convenience class containing logic for dealing with Java package directory structures and
   * package paths.
   */
  public static class PackagePathResolver {
    private final JavaFileParser javaFileParser;
    private final ProjectFilesystem projectFilesystem;

    /**
     * @param javaFileParser parser to read Java sources with.
     * @param projectFilesystem filesystem.
     */
    public PackagePathResolver(JavaFileParser javaFileParser, ProjectFilesystem projectFilesystem) {
      this.javaFileParser = javaFileParser;
      this.projectFilesystem = projectFilesystem;
    }

    /**
     * Tries to return the package path from a Java source file. Returns empty if the file could not
     * be parsed.
     *
     * @param sourcePath path to the java source file.
     * @return the parsed path.
     */
    public Optional<Path> getPackagePathFromSource(Path sourcePath) {
      // Try to read a small subset of the file to extract just the package line
      Optional<String> packageNameFromSource =
          Optionals.bind(
              getPackageSourceLineIfFileExists(sourcePath, projectFilesystem),
              javaFileParser::getPackageNameFromSource);
      // Fall back to parsing the whole file
      if (!packageNameFromSource.isPresent()) {
        packageNameFromSource =
            Optionals.bind(
                projectFilesystem.readFileIfItExists(sourcePath),
                javaFileParser::getPackageNameFromSource);
      }
      return packageNameFromSource.map(ParsingJavaPackageFinder::findPackageFolderWithJavaPackage);
    }

    /**
     * Given a file at `a/b/c/D.java`, and full name of the class is `b.c.D`, the source root for
     * that file is `a`. Returns empty if either the file could not be parsed or the directory
     * structure does not match the package.
     *
     * @param sourcePath path to the java source file.
     * @return the path to source root.
     */
    public Optional<Path> getSourceRootFromSource(Path sourcePath) {
      Path normalizedSourcePath = sourcePath.normalize();
      if (!normalizedSourcePath.toString().endsWith(".java")) {
        return Optional.empty();
      }
      Path sourceDirectoryPath = normalizedSourcePath.getParent();
      return getPackagePathFromSource(normalizedSourcePath)
          .flatMap(
              packagePath ->
                  sourceDirectoryPath.endsWith(packagePath)
                      ? Optional.of(
                          sourceDirectoryPath.subpath(
                              0, sourceDirectoryPath.getNameCount() - packagePath.getNameCount()))
                      : Optional.empty());
    }
  }

  private static Optional<String> getPackageSourceLineIfFileExists(
      Path pathRelativeToProjectRoot, ProjectFilesystem projectFilesystem) {
    if (!projectFilesystem.isFile(pathRelativeToProjectRoot, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                projectFilesystem.newFileInputStream(pathRelativeToProjectRoot)))) {
      StringBuilder block = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (END_OF_PRELUDE.matcher(line).find()) {
          break;
        }
        block.append(line);
      }
      return Optional.of(block.toString());
    } catch (IOException e) {
      logger.warn(
          e,
          "Unable to read source file when trying to determine its package: "
              + pathRelativeToProjectRoot);
      return Optional.empty();
    }
  }

  private static Path findPackageFolderWithJavaPackage(String javaPackage) {
    return Paths.get(javaPackage.replace('.', File.separatorChar));
  }

  private static class CacheBasedPackageFinder implements JavaPackageFinder {
    private JavaPackageFinder fallbackPackageFinder;
    private JavaPackagePathCache packagePathCache;

    public CacheBasedPackageFinder(
        JavaPackageFinder fallbackPackageFinder, JavaPackagePathCache packagePathCache) {
      this.fallbackPackageFinder = fallbackPackageFinder;
      this.packagePathCache = packagePathCache;
    }

    @Override
    public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
      Optional<Path> packageFolder = packagePathCache.lookup(pathRelativeToProjectRoot);
      if (!packageFolder.isPresent()) {
        packageFolder =
            Optional.of(fallbackPackageFinder.findJavaPackageFolder(pathRelativeToProjectRoot));
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
