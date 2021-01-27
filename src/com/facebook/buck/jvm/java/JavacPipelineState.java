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
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  private final ResolvedJavacOptions resolvedJavacOptions;
  private final BuildTargetValue invokingRule;
  private final ResolvedJavac resolvedJavac;
  private final ClasspathChecker classpathChecker;
  @Nullable private final JarParameters abiJarParameters;
  @Nullable private final JarParameters libraryJarParameters;
  private final boolean withDownwardApi;

  private final List<AutoCloseable> closeables = new ArrayList<>();

  @Nullable private CapturingPrintStream stdout;
  @Nullable private CapturingPrintStream stderr;
  @Nullable private ResolvedJavac.Invocation invocation;

  public JavacPipelineState(
      ResolvedJavac resolvedJavac,
      ResolvedJavacOptions resolvedJavacOptions,
      BuildTargetValue invokingRule,
      ClasspathChecker classpathChecker,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      boolean withDownwardApi) {
    this.resolvedJavac = resolvedJavac;
    this.invokingRule = invokingRule;
    this.classpathChecker = classpathChecker;
    this.compilerParameters = compilerParameters;
    this.abiJarParameters = abiJarParameters;
    this.libraryJarParameters = libraryJarParameters;
    this.withDownwardApi = withDownwardApi;
    this.resolvedJavacOptions = resolvedJavacOptions;
  }

  public boolean isRunning() {
    return invocation != null;
  }

  /** Get the invocation instance. */
  public ResolvedJavac.Invocation getJavacInvocation(
      CompilerOutputPathsValue compilerOutputPathsValue,
      IsolatedExecutionContext context,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      RelPath configuredBuckOut)
      throws IOException {
    if (invocation == null) {
      resolvedJavacOptions.validateClasspath(classpathChecker::validateClasspath);

      stdout = new CapturingPrintStream();
      closeables.add(stdout);
      stderr = new CapturingPrintStream();
      closeables.add(stderr);
      Verbosity verbosity =
          context.getVerbosity().isSilent()
              ? Verbosity.STANDARD_INFORMATION
              : context.getVerbosity();
      IsolatedExecutionContext firstOrderContext =
          context.createSubContext(stdout, stderr, Optional.of(verbosity));
      closeables.add(firstOrderContext);

      ProcessExecutor processExecutor = firstOrderContext.getProcessExecutor();
      if (withDownwardApi) {
        processExecutor = context.getDownwardApiProcessExecutor();
      }

      JavacExecutionContext javacExecutionContext =
          ImmutableJavacExecutionContext.ofImpl(
              new JavacEventSinkToBuckEventBusBridge(firstOrderContext.getIsolatedEventBus()),
              stderr,
              firstOrderContext.getClassLoaderCache(),
              verbosity,
              cellToPathMappings,
              context.getRuleCellRoot(),
              firstOrderContext.getEnvironment(),
              processExecutor,
              configuredBuckOut);

      invocation =
          getResolvedJavac()
              .newBuildInvocation(
                  javacExecutionContext,
                  invokingRule,
                  compilerOutputPathsValue,
                  getOptions(context, compilerParameters.getClasspathEntries()),
                  resolvedJavacOptions.getAnnotationProcessors(),
                  resolvedJavacOptions.getJavaPlugins(),
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
    return Objects.requireNonNull(stdout).getContentsAsString(StandardCharsets.UTF_8);
  }

  public String getStderrContents() {
    return Objects.requireNonNull(stderr).getContentsAsString(StandardCharsets.UTF_8);
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

  ResolvedJavac getResolvedJavac() {
    return resolvedJavac;
  }

  /**
   * Returns a list of command-line options to pass to javac. These options reflect the
   * configuration of this javac command.
   *
   * @return list of String command-line options.
   */
  ImmutableList<String> getOptions(
      IsolatedExecutionContext context, ImmutableSortedSet<RelPath> buildClasspathEntries) {
    CompilerOutputPaths outputPaths = compilerParameters.getOutputPaths();
    return getOptions(
        outputPaths.getClassesDir(),
        outputPaths.getAnnotationPath().getPath(),
        context,
        buildClasspathEntries);
  }

  private ImmutableList<String> getOptions(
      RelPath outputDirectory,
      Path generatedCodeDirectory,
      IsolatedExecutionContext context,
      ImmutableSortedSet<RelPath> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    AbsPath ruleCellRoot = context.getRuleCellRoot();
    JavacOptions.appendOptionsTo(
        new OptionsConsumer() {
          @Override
          public void addOptionValue(String option, String value) {
            if (option.equals("bootclasspath")) {
              builder
                  .add("-bootclasspath")
                  .add(
                      Arrays.stream(value.split(File.pathSeparator))
                          .map(path -> ruleCellRoot.resolve(path).toString())
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
        resolvedJavacOptions,
        ruleCellRoot);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-verbose");
    }

    // Specify the output directory.
    builder.add("-d").add(ruleCellRoot.resolve(outputDirectory).toString());

    if (resolvedJavacOptions.isJavaAnnotationProcessorParamsPresent()) {
      builder.add("-s").add(ruleCellRoot.resolve(generatedCodeDirectory).toString());
    }

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath =
          Joiner.on(File.pathSeparator)
              .join(
                  RichStream.from(buildClasspathEntries)
                      .map(ruleCellRoot::resolve)
                      .map(AbsPath::normalize)
                      .iterator());
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
