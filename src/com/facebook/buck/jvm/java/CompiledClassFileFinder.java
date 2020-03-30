/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.util.ZipFileTraversal;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;

/** Searches a compiled {@link JavaLibrary} for the class names of the srcs of the library. */
class CompiledClassFileFinder {

  private final Set<String> classNamesForSources;

  CompiledClassFileFinder(JavaLibrary rule, SourcePathResolverAdapter pathResolver) {
    Path outputPath;
    SourcePath outputSourcePath = rule.getSourcePathToOutput();
    if (outputSourcePath != null) {
      outputPath = pathResolver.getAbsolutePath(outputSourcePath);
    } else {
      outputPath = null;
    }
    classNamesForSources =
        getClassNamesForSources(
            rule.getJavaSrcs(), outputPath, rule.getProjectFilesystem(), pathResolver);
  }

  public Set<String> getClassNamesForSources() {
    return classNamesForSources;
  }

  /**
   * When a collection of .java files is compiled into a directory, that directory will have a
   * subfolder structure that matches the package structure of the input .java files. In general,
   * the .java files will be 1:1 with the .class files with two notable exceptions: (1) There will
   * be an additional .class file for each inner/anonymous class generated. These types of classes
   * are easy to identify because they will contain a '$' in the name. (2) A .java file that defines
   * multiple top-level classes (yes, this can exist:
   * http://stackoverflow.com/questions/2336692/java-multiple-class-declarations-in-one-file) will
   * generate multiple .class files that do not have '$' in the name. In this method, we perform a
   * strict check for (1) and use a heuristic for (2). It is possible to filter out the type (2)
   * situation with a stricter check that aligns the package directories of the .java files and the
   * .class files, but it is a pain to implement. If this heuristic turns out to be insufficient in
   * practice, then we can fix it.
   *
   * @param sources paths to .java source files that were passed to javac
   * @param jarFilePath jar where the generated .class files were written
   */
  static ImmutableSet<String> getClassNamesForSources(
      Set<SourcePath> sources,
      @Nullable Path jarFilePath,
      ProjectFilesystem projectFilesystem,
      SourcePathResolverAdapter resolver) {
    if (jarFilePath == null) {
      return ImmutableSet.of();
    }

    Set<String> sourceClassNames = Sets.newHashSetWithExpectedSize(sources.size());
    try {
      JavaPaths.getExpandedSourcePaths(
              sources.stream()
                  .map(resolver::getAbsolutePath)
                  .collect(ImmutableList.toImmutableList()))
          .stream()
          .map(MorePaths::getNameWithoutExtension)
          .forEach(sourceClassNames::add);
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(
          e, "When determining possible java test class names.");
    }

    ImmutableSet.Builder<String> testClassNames = ImmutableSet.builder();
    Path jarFile = projectFilesystem.getPathForRelativePath(jarFilePath);
    ZipFileTraversal traversal =
        new ZipFileTraversal(jarFile) {

          @Override
          public void visit(ZipFile zipFile, ZipEntry zipEntry) {
            String name = new File(zipEntry.getName()).getName();

            // Ignore non-.class files.
            if (!name.endsWith(".class")) {
              return;
            }

            // As a heuristic for case (2) as described in the Javadoc, make sure the name of the
            // .class file matches the name of a .java/.scala/.xxx file.
            String nameWithoutDotClass = name.substring(0, name.length() - ".class".length());
            if (!sourceClassNames.contains(nameWithoutDotClass)) {
              return;
            }

            // Make sure it is a .class file that corresponds to a top-level .class file and not
            // an
            // inner class.
            if (!name.contains("$")) {
              String fullyQualifiedNameWithDotClassSuffix = zipEntry.getName().replace('/', '.');
              String className =
                  fullyQualifiedNameWithDotClassSuffix.substring(
                      0, fullyQualifiedNameWithDotClassSuffix.length() - ".class".length());
              testClassNames.add(className);
            }
          }
        };
    try {
      traversal.traverse();
    } catch (IOException e) {
      // There's nothing sane to do here. The jar file really should exist.
      throw new RuntimeException(e);
    }

    return testClassNames.build();
  }
}
