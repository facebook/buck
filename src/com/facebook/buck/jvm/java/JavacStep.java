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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.CompilerErrorEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/** Command used to compile java libraries with a variety of ways to handle dependencies. */
public class JavacStep implements Step {

  private final JavacPipelineState pipeline;

  private final BuildTarget invokingRule;

  private final boolean ownsPipelineObject;
  private final SourcePathResolver resolver;
  private final ProjectFilesystem filesystem;

  public JavacStep(
      Javac javac,
      JavacOptions javacOptions,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ClasspathChecker classpathChecker,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters) {
    this(
        new JavacPipelineState(
            javac,
            javacOptions,
            invokingRule,
            classpathChecker,
            compilerParameters,
            abiJarParameters,
            libraryJarParameters),
        invokingRule,
        true,
        resolver,
        filesystem);
  }

  public JavacStep(
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem) {
    this(pipeline, invokingRule, false, resolver, filesystem);
  }

  private JavacStep(
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      boolean ownsPipelineObject,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem) {
    this.pipeline = pipeline;
    this.invokingRule = invokingRule;
    this.ownsPipelineObject = ownsPipelineObject;
    this.resolver = resolver;
    this.filesystem = filesystem;
  }

  @Override
  public final StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    int declaredDepsBuildResult;
    String firstOrderStdout;
    String firstOrderStderr;
    Optional<String> returnedStderr;
    try {
      Javac.Invocation invocation = pipeline.getJavacInvocation(resolver, filesystem, context);
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
    if (declaredDepsBuildResult != 0) {
      returnedStderr =
          processBuildFailure(context.getBuckEventBus(), firstOrderStdout, firstOrderStderr);
    } else {
      returnedStderr = Optional.empty();
    }
    return StepExecutionResult.of(declaredDepsBuildResult, returnedStderr);
  }

  private Optional<String> processBuildFailure(
      BuckEventBus buckEventBus, String firstOrderStdout, String firstOrderStderr) {
    ImmutableList.Builder<String> errorMessage = ImmutableList.builder();
    errorMessage.add(firstOrderStderr);

    ImmutableSet<String> suggestions = ImmutableSet.of();
    CompilerErrorEvent evt =
        CompilerErrorEvent.create(
            invokingRule, firstOrderStderr, CompilerErrorEvent.CompilerType.Java, suggestions);
    buckEventBus.post(evt);

    if (!firstOrderStdout.isEmpty()) {
      buckEventBus.post(ConsoleEvent.info("%s", firstOrderStdout));
    }
    return Optional.of(Joiner.on("\n").join(errorMessage.build()));
  }

  @VisibleForTesting
  Javac getJavac() {
    return pipeline.getJavac();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    String description =
        getJavac()
            .getDescription(
                getOptions(context, getClasspathEntries()),
                pipeline.getCompilerParameters().getSourceFilePaths(),
                pipeline.getCompilerParameters().getOutputPaths().getPathToSourcesList());

    if (JavaAbis.isLibraryTarget(invokingRule) && pipeline.getLibraryJarParameters().isPresent()) {
      JarParameters jarParameters = pipeline.getLibraryJarParameters().get();
      Optional<Path> manifestFile = jarParameters.getManifestFile();
      ImmutableSortedSet<Path> entriesToJar = jarParameters.getEntriesToJar();
      description =
          description
              + "; "
              + String.format(
                  "jar %s %s %s %s",
                  manifestFile.isPresent() ? "cfm" : "cf",
                  jarParameters.getJarPath(),
                  manifestFile.isPresent() ? manifestFile.get() : "",
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
      name = getJavac().getShortName();
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
      ExecutionContext context, ImmutableSortedSet<Path> buildClasspathEntries) {
    return pipeline.getOptions(context, buildClasspathEntries, filesystem, resolver);
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
}
