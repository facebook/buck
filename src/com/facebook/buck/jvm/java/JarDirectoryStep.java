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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.zip.JarBuilder;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Creates a JAR file from a collection of directories/ZIP/JAR files. */
public class JarDirectoryStep implements Step {

  private final ProjectFilesystem filesystem;

  /** Where to write the new JAR file. */
  private final Path pathToOutputFile;

  /** A collection of directories/ZIP/JAR files to include in the generated JAR file. */
  private final ImmutableSortedSet<Path> entriesToJar;

  /** If specified, the Main-Class to list in the manifest of the generated JAR file. */
  @Nullable private final String mainClass;

  /** If specified, the Manifest file to use for the generated JAR file. */
  @Nullable private final Path manifestFile;

  /** Indicates that manifest merging should occur. Defaults to true. */
  private final boolean mergeManifests;

  /** Indicates that the manifest should contain hashes of the entries. Defaults to false. */
  private final boolean hashEntries;

  /** A set of regex. If a file matches one of the regex it will not be included in the Jar. */
  private final ImmutableSet<Pattern> blacklist;

  public JarDirectoryStep(
      ProjectFilesystem filesystem,
      Path pathToOutputFile,
      ImmutableSortedSet<Path> entriesToJar,
      @Nullable String mainClass,
      @Nullable Path manifestFile) {
    this(
        filesystem,
        pathToOutputFile,
        entriesToJar,
        mainClass,
        manifestFile,
        true,
        ImmutableSet.of());
  }

  public JarDirectoryStep(
      ProjectFilesystem filesystem,
      Path pathToOutputFile,
      ImmutableSortedSet<Path> entriesToJar,
      @Nullable String mainClass,
      @Nullable Path manifestFile,
      boolean mergeManifests,
      ImmutableSet<Pattern> blacklist) {
    this(
        filesystem,
        pathToOutputFile,
        entriesToJar,
        mainClass,
        manifestFile,
        mergeManifests,
        false,
        blacklist);
  }
  /**
   * Creates a JAR from the specified entries (most often, classpath entries).
   *
   * <p>If an entry is a directory, then its files are traversed and added to the generated JAR.
   *
   * <p>If an entry is a file, then it is assumed to be a ZIP/JAR file, and its entries will be read
   * and copied to the generated JAR.
   *
   * @param pathToOutputFile The directory that contains this path must exist before this command is
   *     executed.
   * @param entriesToJar Paths to directories/ZIP/JAR files.
   * @param mainClass If specified, the value for the Main-Class attribute in the manifest of the
   *     generated JAR.
   * @param manifestFile If specified, the path to the manifest file to use with this JAR.
   */
  public JarDirectoryStep(
      ProjectFilesystem filesystem,
      Path pathToOutputFile,
      ImmutableSortedSet<Path> entriesToJar,
      @Nullable String mainClass,
      @Nullable Path manifestFile,
      boolean mergeManifests,
      boolean hashEntries,
      ImmutableSet<Pattern> blacklist) {
    this.filesystem = filesystem;
    this.pathToOutputFile = pathToOutputFile;
    this.entriesToJar = entriesToJar;
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
    this.mergeManifests = mergeManifests;
    this.hashEntries = hashEntries;
    this.blacklist = blacklist;
  }

  private String getJarArgs() {
    String result = "cf";
    if (manifestFile != null) {
      result += "m";
    }
    return result;
  }

  @Override
  public String getShortName() {
    return "jar";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "jar %s %s %s %s",
        getJarArgs(),
        pathToOutputFile,
        manifestFile != null ? manifestFile : "",
        Joiner.on(' ').join(entriesToJar));
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {

    JavacEventSinkToBuckEventBusBridge eventSink =
        new JavacEventSinkToBuckEventBusBridge(context.getBuckEventBus());
    return StepExecutionResult.of(
        new JarBuilder()
            .setObserver(new LoggingJarBuilderObserver(eventSink))
            .setEntriesToJar(entriesToJar.stream().map(filesystem::resolve))
            .setMainClass(Optional.ofNullable(mainClass).orElse(null))
            .setManifestFile(manifestFile != null ? filesystem.resolve(manifestFile) : null)
            .setShouldMergeManifests(mergeManifests)
            .setShouldHashEntries(hashEntries)
            .setEntryPatternBlacklist(blacklist)
            .createJarFile(filesystem.resolve(pathToOutputFile)));
  }
}
