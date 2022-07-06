/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.CompilerOutputPaths.getKotlinTempDepFilePath;
import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.DefaultClassUsageFileWriter;
import com.facebook.buck.jvm.kotlin.plugin.PluginLoader;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/** Kotlin compile Step */
public class KotlincStep extends IsolatedStep {

  private static final String CLASSPATH_FLAG = "-classpath";
  private static final String DESTINATION_FLAG = "-d";
  private static final String INCLUDE_RUNTIME_FLAG = "-include-runtime";
  private static final String EXCLUDE_REFLECT = "-no-reflect";
  private static final String X_PLUGIN_ARG = "-Xplugin=";
  private static final String PLUGIN = "-P";
  private static final int EXPECTED_SOURCE_ONLY_ABI_EXIT_CODE = 2;

  private final Kotlinc kotlinc;
  private final ImmutableSortedSet<AbsPath> combinedClassPathEntries;
  private final ImmutableSortedSet<AbsPath> kotlinHomeLibraries;
  private final Path outputDirectory;
  private final ImmutableList<String> extraArguments;
  private final ImmutableList<String> verboseModeOnlyExtraArguments;
  private final ImmutableSortedSet<RelPath> sourceFilePaths;
  private final Path pathToSrcsList;
  private final RelPath reportDirPath;
  private final BuildTargetValue invokingRule;
  private final CompilerOutputPaths outputPaths;
  private final boolean withDownwardApi;
  private final boolean trackClassUsage;
  private final RelPath configuredBuckOut;
  private final ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings;
  private final ImmutableMap<String, AbsPath> resolvedKosabiPluginOptionPath;
  private final ImmutableSortedSet<AbsPath> sourceOnlyAbiClasspath;
  private final boolean verifySourceOnlyAbiConstraints;

  KotlincStep(
      BuildTargetValue invokingRule,
      Path outputDirectory,
      ImmutableSortedSet<RelPath> sourceFilePaths,
      Path pathToSrcsList,
      ImmutableSortedSet<AbsPath> combinedClassPathEntries,
      ImmutableSortedSet<AbsPath> kotlinHomeLibraries,
      RelPath reportDirPath,
      Kotlinc kotlinc,
      ImmutableList<String> extraArguments,
      ImmutableList<String> verboseModeOnlyExtraArguments,
      CompilerOutputPaths outputPaths,
      boolean withDownwardApi,
      boolean trackClassUsage,
      RelPath configuredBuckOut,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      ImmutableMap<String, AbsPath> resolvedKosabiPluginOptionPath,
      ImmutableSortedSet<AbsPath> sourceOnlyAbiClasspath,
      boolean verifySourceOnlyAbiConstraints) {
    this.invokingRule = invokingRule;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.reportDirPath = reportDirPath;
    this.kotlinc = kotlinc;
    this.combinedClassPathEntries = combinedClassPathEntries;
    this.kotlinHomeLibraries = kotlinHomeLibraries;
    this.extraArguments = extraArguments;
    this.verboseModeOnlyExtraArguments = verboseModeOnlyExtraArguments;
    this.outputPaths = outputPaths;
    this.withDownwardApi = withDownwardApi;
    this.trackClassUsage = trackClassUsage;
    this.configuredBuckOut = configuredBuckOut;
    this.cellToPathMappings = cellToPathMappings;
    this.resolvedKosabiPluginOptionPath = resolvedKosabiPluginOptionPath;
    this.sourceOnlyAbiClasspath = sourceOnlyAbiClasspath;
    this.verifySourceOnlyAbiConstraints = verifySourceOnlyAbiConstraints;
  }

  @Override
  public String getShortName() {
    return getKotlinc().getShortName();
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    Verbosity verbosity =
        context.getVerbosity().isSilent() ? Verbosity.STANDARD_INFORMATION : context.getVerbosity();

    try (CapturingPrintStream stdout = new CapturingPrintStream();
        CapturingPrintStream stderr = new CapturingPrintStream();
        IsolatedExecutionContext firstOrderContext =
            context.createSubContext(stdout, stderr, Optional.of(verbosity))) {

      int declaredDepsBuildResult =
          kotlinc.buildWithClasspath(
              firstOrderContext,
              invokingRule,
              getOptions(context, combinedClassPathEntries),
              kotlinHomeLibraries,
              sourceFilePaths,
              pathToSrcsList,
              Optional.of(outputPaths.getWorkingDirectory().getPath()),
              context.getRuleCellRoot(),
              withDownwardApi);

      String firstOrderStderr = stderr.getContentsAsString(StandardCharsets.UTF_8);
      Optional<String> returnedStderr;

      // We're generating Kotlin source-only-abi with Kosabi, a set of Kotlin compiler plugins.
      // see `Kosabi.java`
      //
      // `jvm-abi-gen` is one of Kosabi plugins responsible for ABI class files generation.
      // `jvm-abi-gen` could pass only the Kotlin Frontend Compiler stage, thus it's intentionally
      // throws an Internal Compiler Error and exits with the corresponding exit code.
      //
      // EXPECTED_SOURCE_ONLY_ABI_EXIT_CODE is Kotlin compiler Internal Error code.
      // We should treat Internal Compiler Error in source-only-abi as an abi-generation Success.
      if (declaredDepsBuildResult == EXPECTED_SOURCE_ONLY_ABI_EXIT_CODE
          && invokingRule.isSourceOnlyAbi()) {
        declaredDepsBuildResult = StepExecutionResults.SUCCESS_EXIT_CODE;
        returnedStderr = Optional.empty();
      } else if (declaredDepsBuildResult != StepExecutionResults.SUCCESS_EXIT_CODE) {
        returnedStderr = Optional.of(firstOrderStderr);
      } else {
        returnedStderr = Optional.empty();

        if (trackClassUsage) {
          AbsPath ruleCellRoot = context.getRuleCellRoot();
          RelPath outputJarDirPath = outputPaths.getOutputJarDirPath();
          new DefaultClassUsageFileWriter()
              .writeFile(
                  KotlinClassUsageHelper.getClassUsageData(reportDirPath, ruleCellRoot),
                  CompilerOutputPaths.getKotlinDepFilePath(outputJarDirPath),
                  ruleCellRoot,
                  configuredBuckOut,
                  cellToPathMappings);
        }
      }
      return StepExecutionResult.builder()
          .setExitCode(declaredDepsBuildResult)
          .setStderr(returnedStderr)
          .build();
    }
  }

  @VisibleForTesting
  Kotlinc getKotlinc() {
    return kotlinc;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return getKotlinc()
        .getDescription(
            getOptions(context, getClasspathEntries()), sourceFilePaths, pathToSrcsList);
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
      IsolatedExecutionContext context, ImmutableSortedSet<AbsPath> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    AbsPath ruleCellRoot = context.getRuleCellRoot();

    if (outputDirectory != null) {
      builder.add(DESTINATION_FLAG, ruleCellRoot.resolve(outputDirectory).toString());
    }

    if (invokingRule.isSourceOnlyAbi()) {
      if (resolvedKosabiPluginOptionPath.containsKey(
          KosabiConfig.PROPERTY_KOSABI_STUBS_GEN_PLUGIN)) {
        AbsPath stubPlugin =
            resolvedKosabiPluginOptionPath.get(KosabiConfig.PROPERTY_KOSABI_STUBS_GEN_PLUGIN);
        builder.add(X_PLUGIN_ARG + stubPlugin);
      }
      if (resolvedKosabiPluginOptionPath.containsKey(
          KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN)) {
        AbsPath jvmAbiPlugin =
            resolvedKosabiPluginOptionPath.get(KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN);
        builder.add(X_PLUGIN_ARG + jvmAbiPlugin);
      }
      builder.add(PLUGIN);
      builder.add("plugin:com.facebook.jvm.abi.gen:outputDir=" + outputPaths.getClassesDir());

      addClasspath(builder, this.sourceOnlyAbiClasspath);
    } else if (invokingRule.isSourceAbi()) {
      throw new Error("Source ABI flavor is not supported for Kotlin targets");
    } else if (!buildClasspathEntries.isEmpty()) {
      addClasspath(builder, buildClasspathEntries);
    }

    // We expect Kosabi/Applicability to generate a compilation error if
    // a library target verification fails.
    // User will see a broken compilation with the following message:
    // Kosabi/Applicability FAILED on this target ...
    if (verifySourceOnlyAbiConstraints && invokingRule.isLibraryJar()) {
      if (resolvedKosabiPluginOptionPath.containsKey(
          KosabiConfig.PROPERTY_KOSABI_APPLICABILITY_PLUGIN)) {
        AbsPath applicabilityPlugin =
            resolvedKosabiPluginOptionPath.get(KosabiConfig.PROPERTY_KOSABI_APPLICABILITY_PLUGIN);
        builder.add(X_PLUGIN_ARG + applicabilityPlugin);
      }
    }

    builder.add(INCLUDE_RUNTIME_FLAG);
    builder.add(EXCLUDE_REFLECT);

    if (trackClassUsage) {
      builder.add(X_PLUGIN_ARG + PluginLoader.DEP_TRACKER_KOTLINC_PLUGIN_JAR_PATH);
      builder.add(PLUGIN);
      builder.add(
          "plugin:buck_deps_tracker:out="
              + ruleCellRoot.resolve(getKotlinTempDepFilePath(reportDirPath)));
    }

    if (!extraArguments.isEmpty()) {
      for (String extraArgument : extraArguments) {
        if (!extraArgument.isEmpty()) {
          builder.add(extraArgument);
        }
      }
    }

    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()
        && !verboseModeOnlyExtraArguments.isEmpty()) {
      for (String extraArgument : verboseModeOnlyExtraArguments) {
        if (!extraArgument.isEmpty()) {
          builder.add(extraArgument);
        }
      }
    }

    return builder.build();
  }

  private void addClasspath(ImmutableList.Builder<String> builder, Iterable<AbsPath> pathElements) {
    builder.add(
        CLASSPATH_FLAG,
        Joiner.on(File.pathSeparator)
            .join(transform(pathElements, path -> path.getPath().toString())));
  }

  /** @return The classpath entries used to invoke javac. */
  @VisibleForTesting
  ImmutableSortedSet<AbsPath> getClasspathEntries() {
    return combinedClassPathEntries;
  }
}
