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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.event.external.events.CompilerErrorEventExternalInterface;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/** Command used to compile java libraries with a variety of ways to handle dependencies. */
public class JavacStep extends IsolatedStep {

  private final JavacPipelineState pipeline;
  private final BuildTarget invokingRule;
  private final boolean ownsPipelineObject;
  private final BaseBuckPaths buckPaths;
  private final ImmutableMap<String, RelPath> cellToPathMappings;

  public JavacStep(
      ResolvedJavac resolvedJavac,
      ResolvedJavacOptions javacOptions,
      BuildTarget invokingRule,
      BaseBuckPaths buckPaths,
      ClasspathChecker classpathChecker,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      boolean withDownwardApi,
      ImmutableMap<String, RelPath> cellToPathMappings) {
    this(
        new JavacPipelineState(
            resolvedJavac,
            javacOptions,
            invokingRule,
            classpathChecker,
            compilerParameters,
            abiJarParameters,
            libraryJarParameters,
            withDownwardApi),
        invokingRule,
        true,
        buckPaths,
        cellToPathMappings);
  }

  public JavacStep(
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      BaseBuckPaths buckPaths,
      ImmutableMap<String, RelPath> cellToPathMappings) {
    this(pipeline, invokingRule, false, buckPaths, cellToPathMappings);
  }

  private JavacStep(
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      boolean ownsPipelineObject,
      BaseBuckPaths buckPaths,
      ImmutableMap<String, RelPath> cellToPathMappings) {
    this.pipeline = pipeline;
    this.invokingRule = invokingRule;
    this.ownsPipelineObject = ownsPipelineObject;
    this.buckPaths = buckPaths;
    this.cellToPathMappings = cellToPathMappings;
  }

  @Override
  public final StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    int declaredDepsBuildResult;
    String firstOrderStdout;
    String firstOrderStderr;
    Optional<String> returnedStderr;
    try {
      ResolvedJavac.Invocation invocation =
          pipeline.getJavacInvocation(buckPaths, context, cellToPathMappings);
      if (JavaAbis.isSourceAbiTarget(invokingRule)) {
        declaredDepsBuildResult = invocation.buildSourceAbiJar();
      } else if (JavaAbis.isSourceOnlyAbiTarget(invokingRule)) {
        declaredDepsBuildResult = invocation.buildSourceOnlyAbiJar();
      } else {
        declaredDepsBuildResult = invocation.buildClasses();
      }
      firstOrderStdout = pipeline.getStdoutContents();
      firstOrderStderr = pipeline.getStderrContents();
    } finally {
      if (ownsPipelineObject) {
        pipeline.close();
      }
    }
    if (declaredDepsBuildResult != StepExecutionResults.SUCCESS_EXIT_CODE) {
      returnedStderr =
          processBuildFailure(context.getIsolatedEventBus(), firstOrderStdout, firstOrderStderr);
    } else {
      returnedStderr = Optional.empty();
    }
    return StepExecutionResult.builder()
        .setExitCode(declaredDepsBuildResult)
        .setStderr(returnedStderr)
        .build();
  }

  private Optional<String> processBuildFailure(
      IsolatedEventBus buckEventBus, String stdOut, String stdErr) {
    ImmutableList.Builder<String> errorMessage = ImmutableList.builder();
    errorMessage.add(stdErr);

    ExternalEvent errorEvent =
        new ExternalEvent(
            ImmutableMap.of(
                BuckEventExternalInterface.EVENT_TYPE_KEY,
                CompilerErrorEventExternalInterface.COMPILER_ERROR_EVENT,
                CompilerErrorEventExternalInterface.ERROR_MESSAGE_KEY,
                stdErr,
                CompilerErrorEventExternalInterface.BUILD_TARGET_NAME_KEY,
                invokingRule.getFullyQualifiedName(),
                CompilerErrorEventExternalInterface.COMPILER_NAME_KEY,
                getClass().getSimpleName()));
    buckEventBus.post(errorEvent);

    if (!stdOut.isEmpty()) {
      buckEventBus.post(ConsoleEvent.info("%s", stdOut));
    }
    return Optional.of(Joiner.on("\n").join(errorMessage.build()));
  }

  @VisibleForTesting
  ResolvedJavac getResolvedJavac() {
    return pipeline.getResolvedJavac();
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    String description =
        getResolvedJavac()
            .getDescription(
                getOptions(context, getClasspathEntries()),
                pipeline.getCompilerParameters().getSourceFilePaths(),
                pipeline.getCompilerParameters().getOutputPaths().getPathToSourcesList());

    if (JavaAbis.isLibraryTarget(invokingRule) && pipeline.getLibraryJarParameters().isPresent()) {
      JarParameters jarParameters = pipeline.getLibraryJarParameters().get();
      Optional<RelPath> manifestFile = jarParameters.getManifestFile();
      ImmutableSortedSet<RelPath> entriesToJar = jarParameters.getEntriesToJar();
      description =
          description
              + "; "
              + String.format(
                  "jar %s %s %s %s",
                  manifestFile.map(ignore -> "cfm").orElse("cf"),
                  jarParameters.getJarPath(),
                  manifestFile.map(RelPath::toString).orElse(""),
                  Joiner.on(' ').join(entriesToJar));
    }

    return description;
  }

  @Override
  public String getShortName() {
    String name;
    if (JavaAbis.isSourceAbiTarget(invokingRule)) {
      return "source_abi";
    } else if (JavaAbis.isSourceOnlyAbiTarget(invokingRule)) {
      return "source_only_abi";
    } else if (pipeline.getLibraryJarParameters().isPresent()) {
      name = "javac_jar";
    } else {
      name = getResolvedJavac().getShortName();
    }

    return name;
  }

  /**
   * Returns a list of command-line options to pass to javac. These options reflect the
   * configuration of this javac command.
   *
   * @param context the ExecutionContext with in which javac will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  ImmutableList<String> getOptions(
      IsolatedExecutionContext context, ImmutableSortedSet<Path> buildClasspathEntries) {
    return pipeline.getOptions(context, buildClasspathEntries);
  }

  /** @return The classpath entries used to invoke javac. */
  @VisibleForTesting
  ImmutableSortedSet<Path> getClasspathEntries() {
    return pipeline.getCompilerParameters().getClasspathEntries();
  }

  @VisibleForTesting
  ImmutableSortedSet<Path> getSrcs() {
    return pipeline.getCompilerParameters().getSourceFilePaths();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("invokingRule", invokingRule)
        .add("ownsPipelineObject", ownsPipelineObject)
        .toString();
  }
}
