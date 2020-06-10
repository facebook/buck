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

package com.facebook.buck.step.isolatedsteps.java;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.JavacEventSinkToBuckEventBusBridge;
import com.facebook.buck.jvm.java.LoggingJarBuilderObserver;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.zip.JarBuilder;
import com.google.common.base.MoreObjects;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Creates a JAR file from a collection of directories/ZIP/JAR files. */
public class JarDirectoryStep extends IsolatedStep {

  private final JarParameters parameters;

  /**
   * Creates a JAR from the specified entries (most often, classpath entries).
   *
   * <p>If an entry is a directory, then its files are traversed and added to the generated JAR.
   *
   * <p>If an entry is a file, then it is assumed to be a ZIP/JAR file, and its entries will be read
   * and copied to the generated JAR. @Param parameters the parameters that describe how to create
   * the jar.
   */
  public JarDirectoryStep(JarParameters parameters) {
    this.parameters = parameters;
  }

  @Override
  public String getShortName() {
    return "jar";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    Optional<RelPath> manifestFile = parameters.getManifestFile();
    return String.join(
        " ",
        "jar",
        manifestFile.map(ignore -> "cfm").orElse("cf"),
        parameters.getJarPath().toString(),
        manifestFile.map(RelPath::toString).orElse(""),
        parameters.getEntriesToJar().stream()
            .map(RelPath::toString)
            .collect(Collectors.joining(" ")));
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {

    JavacEventSinkToBuckEventBusBridge eventSink =
        new JavacEventSinkToBuckEventBusBridge(context.getBuckEventBus());
    LoggingJarBuilderObserver loggingObserver =
        new LoggingJarBuilderObserver(eventSink, parameters.getDuplicatesLogLevel());
    AbsPath root = context.getRuleCellRoot();
    int exitCode =
        new JarBuilder()
            .setObserver(loggingObserver)
            .setEntriesToJar(parameters.getEntriesToJar().stream().map(resolve(root)))
            .setMainClass(parameters.getMainClass().orElse(null))
            .setManifestFile(parameters.getManifestFile().map(resolve(root)).orElse(null))
            .setShouldMergeManifests(parameters.getMergeManifests())
            .setShouldDisallowAllDuplicates(parameters.getDisallowAllDuplicates())
            .setShouldHashEntries(parameters.getHashEntries())
            .setRemoveEntryPredicate(parameters.getRemoveEntryPredicate())
            .createJarFile(
                ProjectFilesystemUtils.getPathForRelativePath(
                    root, parameters.getJarPath().getPath()));

    return StepExecutionResult.of(exitCode);
  }

  private Function<RelPath, Path> resolve(AbsPath root) {
    return relPath -> ProjectFilesystemUtils.getPathForRelativePath(root, relPath.getPath());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("parameters", parameters).toString();
  }
}
