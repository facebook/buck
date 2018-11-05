/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.jvm.java.classes.ClasspathTraversal;
import com.facebook.buck.jvm.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.jvm.java.classes.FileLikes;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Step} that takes a directory or zip of {@code .class} files and traverses it to get the
 * total set of {@code .class} files included by the directory or zip.
 */
public class AccumulateClassNamesStep implements Step {

  /**
   * In the generated {@code classes.txt} file, each line will contain the path to a {@code .class}
   * file (without its suffix) and the SHA-1 hash of its contents, separated by this separator.
   */
  static final String CLASS_NAME_HASH_CODE_SEPARATOR = " ";

  private static final Splitter CLASS_NAME_AND_HASH_SPLITTER =
      Splitter.on(CLASS_NAME_HASH_CODE_SEPARATOR);

  private final ProjectFilesystem filesystem;
  private final Optional<Path> pathToJarOrClassesDirectory;
  private final Path whereClassNamesShouldBeWritten;

  /**
   * @param pathToJarOrClassesDirectory Where to look for .class files. If absent, then an empty
   *     file will be written to {@code whereClassNamesShouldBeWritten}.
   * @param whereClassNamesShouldBeWritten Path to a file where an alphabetically sorted list of
   *     class files and corresponding SHA-1 hashes of their contents will be written.
   */
  public AccumulateClassNamesStep(
      ProjectFilesystem filesystem,
      Optional<Path> pathToJarOrClassesDirectory,
      Path whereClassNamesShouldBeWritten) {
    this.filesystem = filesystem;
    this.pathToJarOrClassesDirectory = pathToJarOrClassesDirectory;
    this.whereClassNamesShouldBeWritten = whereClassNamesShouldBeWritten;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    ImmutableSortedMap<String, HashCode> classNames;
    if (pathToJarOrClassesDirectory.isPresent()) {
      Optional<ImmutableSortedMap<String, HashCode>> classNamesOptional =
          calculateClassHashes(
              context, filesystem, filesystem.resolve(pathToJarOrClassesDirectory.get()));
      if (classNamesOptional.isPresent()) {
        classNames = classNamesOptional.get();
      } else {
        return StepExecutionResults.ERROR;
      }
    } else {
      classNames = ImmutableSortedMap.of();
    }

    filesystem.writeLinesToPath(
        Iterables.transform(
            classNames.entrySet(),
            entry -> entry.getKey() + CLASS_NAME_HASH_CODE_SEPARATOR + entry.getValue()),
        whereClassNamesShouldBeWritten);

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "get_class_names";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    String sourceString = pathToJarOrClassesDirectory.map(Object::toString).orElse("null");
    return String.format("get_class_names %s > %s", sourceString, whereClassNamesShouldBeWritten);
  }

  /** @return an Optional that will be absent if there was an error. */
  public static Optional<ImmutableSortedMap<String, HashCode>> calculateClassHashes(
      ExecutionContext context, ProjectFilesystem filesystem, Path path) {
    Map<String, HashCode> classNames = new HashMap<>();

    ClasspathTraversal traversal =
        new ClasspathTraversal(Collections.singleton(path), filesystem) {
          @Override
          public void visit(FileLike fileLike) throws IOException {
            // When traversing a JAR file, it may have resources or directory entries that do not
            // end in .class, which should be ignored.
            if (!FileLikes.isClassFile(fileLike)) {
              return;
            }

            String key = FileLikes.getFileNameWithoutClassSuffix(fileLike);
            ByteSource input =
                new ByteSource() {
                  @Override
                  public InputStream openStream() throws IOException {
                    return fileLike.getInput();
                  }
                };
            HashCode value = input.hash(Hashing.sha1());
            HashCode existing = classNames.putIfAbsent(key, value);
            if (existing != null && !existing.equals(value)) {
              throw new IllegalArgumentException(
                  String.format(
                      "Multiple entries with same key but differing values: %1$s=%2$s and %1$s=%3$s",
                      key, value, existing));
            }
          }
        };

    try {
      new DefaultClasspathTraverser().traverse(traversal);
    } catch (IOException e) {
      context.logError(e, "Error accumulating class names for %s.", path);
      return Optional.empty();
    }

    return Optional.of(ImmutableSortedMap.copyOf(classNames, Ordering.natural()));
  }

  /**
   * @param lines that were written in the same format output by {@link #execute(ExecutionContext)}.
   */
  public static ImmutableSortedMap<String, HashCode> parseClassHashes(List<String> lines) {
    Map<String, HashCode> classNames = new HashMap<>();

    for (String line : lines) {
      List<String> parts = CLASS_NAME_AND_HASH_SPLITTER.splitToList(line);
      Preconditions.checkState(parts.size() == 2);
      String key = parts.get(0);
      HashCode value = HashCode.fromString(parts.get(1));
      HashCode existing = classNames.putIfAbsent(key, value);
      if (existing != null && !existing.equals(value)) {
        throw new IllegalArgumentException(
            String.format(
                "Multiple entries with same key but differing values: %1$s=%2$s and %1$s=%3$s",
                key, value, existing));
      }
    }

    return ImmutableSortedMap.copyOf(classNames, Ordering.natural());
  }
}
