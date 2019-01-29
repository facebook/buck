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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.zip.JarBuilder;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.nio.file.Path;

/** Creates a JAR file from a collection of directories/ZIP/JAR files. */
public class JarDirectoryStep implements Step {

  private final ProjectFilesystem filesystem;

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
  public JarDirectoryStep(ProjectFilesystem filesystem, JarParameters parameters) {
    this.filesystem = filesystem;
    this.parameters = parameters;
  }

  private String getJarArgs() {
    String result = "cf";
    if (parameters.getManifestFile().isPresent()) {
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
        parameters.getJarPath(),
        parameters.getManifestFile().map(Path::toString).orElse(""),
        Joiner.on(' ').join(parameters.getEntriesToJar()));
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {

    JavacEventSinkToBuckEventBusBridge eventSink =
        new JavacEventSinkToBuckEventBusBridge(context.getBuckEventBus());
    LoggingJarBuilderObserver loggingObserver =
        new LoggingJarBuilderObserver(eventSink, parameters.getDuplicatesLogLevel());
    return StepExecutionResult.of(
        new JarBuilder()
            .setObserver(loggingObserver)
            .setEntriesToJar(parameters.getEntriesToJar().stream().map(filesystem::resolve))
            .setMainClass(parameters.getMainClass().orElse(null))
            .setManifestFile(parameters.getManifestFile().map(filesystem::resolve).orElse(null))
            .setShouldMergeManifests(parameters.getMergeManifests())
            .setShouldDisallowAllDuplicates(parameters.getDisallowAllDuplicates())
            .setShouldHashEntries(parameters.getHashEntries())
            .setRemoveEntryPredicate(parameters.getRemoveEntryPredicate())
            .createJarFile(filesystem.resolve(parameters.getJarPath())));
  }
}
