/*
 * Copyright 2017-present Facebook, Inc.
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
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class JavacPipelineState implements RulePipelineState {
  private static final Logger LOG = Logger.get(JavacPipelineState.class);

  private final CompilerParameters compilerParameters;
  private final JavacOptions javacOptions;
  private final BuildTarget invokingRule;
  private final Javac javac;
  private final ClasspathChecker classpathChecker;
  @Nullable private final JarParameters abiJarParameters;
  @Nullable private final JarParameters libraryJarParameters;

  private final List<AutoCloseable> closeables = new ArrayList<>();

  @Nullable private CapturingPrintStream stdout;
  @Nullable private CapturingPrintStream stderr;
  @Nullable private Javac.Invocation invocation;

  public JavacPipelineState(
      Javac javac,
      JavacOptions javacOptions,
      BuildTarget invokingRule,
      ClasspathChecker classpathChecker,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters) {
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.invokingRule = invokingRule;
    this.classpathChecker = classpathChecker;
    this.compilerParameters = compilerParameters;
    this.abiJarParameters = abiJarParameters;
    this.libraryJarParameters = libraryJarParameters;
  }

  public boolean isRunning() {
    return invocation != null;
  }

  /** Get the invocation instance. */
  public Javac.Invocation getJavacInvocation(
      SourcePathResolver resolver, ProjectFilesystem filesystem, ExecutionContext context)
      throws IOException {
    if (invocation == null) {
      javacOptions.validateOptions(classpathChecker::validateClasspath);

      stdout = new CapturingPrintStream();
      closeables.add(stdout);
      stderr = new CapturingPrintStream();
      closeables.add(stderr);
      Verbosity verbosity =
          context.getVerbosity().isSilent()
              ? Verbosity.STANDARD_INFORMATION
              : context.getVerbosity();
      ExecutionContext firstOrderContext =
          context.createSubContext(stdout, stderr, Optional.of(verbosity));
      closeables.add(firstOrderContext);

      JavacExecutionContext javacExecutionContext =
          JavacExecutionContext.of(
              new JavacEventSinkToBuckEventBusBridge(firstOrderContext.getBuckEventBus()),
              stderr,
              firstOrderContext.getClassLoaderCache(),
              verbosity,
              firstOrderContext.getCellPathResolver(),
              firstOrderContext.getJavaPackageFinder(),
              filesystem,
              context.getProjectFilesystemFactory(),
              firstOrderContext.getEnvironment(),
              firstOrderContext.getProcessExecutor());

      ImmutableList<JavacPluginJsr199Fields> pluginFields =
          ImmutableList.copyOf(
              javacOptions
                  .getAnnotationProcessingParams()
                  .getModernProcessors()
                  .stream()
                  .map(properties -> properties.getJavacPluginJsr199Fields(resolver, filesystem))
                  .collect(Collectors.toList()));

      invocation =
          getJavac()
              .newBuildInvocation(
                  javacExecutionContext,
                  resolver,
                  invokingRule,
                  getOptions(
                      context, compilerParameters.getClasspathEntries(), filesystem, resolver),
                  pluginFields,
                  compilerParameters.getSourceFilePaths(),
                  compilerParameters.getOutputPaths().getPathToSourcesList(),
                  compilerParameters.getOutputPaths().getWorkingDirectory(),
                  compilerParameters.shouldTrackClassUsage(),
                  compilerParameters.shouldTrackJavacPhaseEvents(),
                  abiJarParameters,
                  libraryJarParameters,
                  compilerParameters.getAbiGenerationMode(),
                  compilerParameters.getAbiCompatibilityMode(),
                  compilerParameters.getSourceOnlyAbiRuleInfoFactory());

      closeables.add(invocation);
    }

    return invocation;
  }

  public String getStdoutContents() {
    return Objects.requireNonNull(stdout).getContentsAsString(Charsets.UTF_8);
  }

  public String getStderrContents() {
    return Objects.requireNonNull(stderr).getContentsAsString(Charsets.UTF_8);
  }

  @Override
  public void close() {
    for (AutoCloseable closeable : Lists.reverse(closeables)) {
      try {
        if (closeable != null) {
          closeable.close();
        }
      } catch (Exception e) {
        LOG.warn(e, "Unable to close %s; we may be leaking memory.", closeable);
      }
    }

    closeables.clear();
    stdout = null;
    stderr = null;
    invocation = null;
  }

  @VisibleForTesting
  Javac getJavac() {
    return javac;
  }

  /**
   * Returns a list of command-line options to pass to javac. These options reflect the
   * configuration of this javac command.
   *
   * @return list of String command-line options.
   */
  @VisibleForTesting
  ImmutableList<String> getOptions(
      ExecutionContext context,
      ImmutableSortedSet<Path> buildClasspathEntries,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver) {
    return getOptions(
        javacOptions,
        filesystem,
        resolver,
        compilerParameters.getOutputPaths().getClassesDir(),
        compilerParameters.getOutputPaths().getAnnotationPath(),
        context,
        buildClasspathEntries);
  }

  public static ImmutableList<String> getOptions(
      JavacOptions javacOptions,
      ProjectFilesystem filesystem,
      SourcePathResolver pathResolver,
      Path outputDirectory,
      Path generatedCodeDirectory,
      ExecutionContext context,
      ImmutableSortedSet<Path> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    javacOptions.appendOptionsTo(
        new OptionsConsumer() {
          @Override
          public void addOptionValue(String option, String value) {
            if (option.equals("bootclasspath")) {
              builder
                  .add("-bootclasspath")
                  .add(
                      Arrays.stream(value.split(File.pathSeparator))
                          .map(path -> filesystem.resolve(path).toString())
                          .collect(Collectors.joining(File.pathSeparator)));
            } else {
              builder.add("-" + option).add(value);
            }
          }

          @Override
          public void addFlag(String flagName) {
            builder.add("-" + flagName);
          }

          @Override
          public void addExtras(Collection<String> extras) {
            builder.addAll(extras);
          }
        },
        pathResolver,
        filesystem);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-verbose");
    }

    // Specify the output directory.
    builder.add("-d").add(filesystem.resolve(outputDirectory).toString());

    if (!javacOptions.getAnnotationProcessingParams().isEmpty()) {
      builder.add("-s").add(filesystem.resolve(generatedCodeDirectory).toString());
    }

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(buildClasspathEntries);
      builder.add("-classpath", classpath);
    } else {
      builder.add("-classpath", "''");
    }

    return builder.build();
  }

  CompilerParameters getCompilerParameters() {
    return compilerParameters;
  }

  Optional<JarParameters> getLibraryJarParameters() {
    return Optional.ofNullable(libraryJarParameters);
  }

  Optional<JarParameters> getAbiJarParameters() {
    return Optional.ofNullable(abiJarParameters);
  }
}
