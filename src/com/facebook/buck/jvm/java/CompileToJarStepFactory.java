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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Provides a base implementation for post compile steps. */
public abstract class CompileToJarStepFactory<T extends CompileToJarStepFactory.ExtraParams>
    implements AddsToRuleKey {

  @AddToRuleKey private final boolean hasAnnotationProcessing;
  @AddToRuleKey protected final boolean withDownwardApi;

  protected CompileToJarStepFactory(boolean hasAnnotationProcessing, boolean withDownwardApi) {
    this.withDownwardApi = withDownwardApi;
    this.hasAnnotationProcessing = hasAnnotationProcessing;
  }

  public final void createCompileToJarStep(
      FilesystemParams filesystemParams,
      BuildTargetValue buildTargetValue,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      AbsPath buildCellRootPath,
      ResolvedJavac resolvedJavac,
      T extraParams) {
    Preconditions.checkArgument(libraryJarParameters != null || abiJarParameters == null);

    steps.addAll(
        getCompilerSetupIsolatedSteps(
            resourcesMap,
            compilerParameters.getOutputPaths(),
            compilerParameters.getSourceFilePaths().isEmpty()));

    JarParameters jarParameters =
        abiJarParameters != null ? abiJarParameters : libraryJarParameters;
    if (jarParameters != null) {
      addJarSetupSteps(jarParameters, steps);
    }

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(
          compilerOutputPathsValue, buildTargetValue, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createCompileToJarStepImpl(
          filesystemParams,
          cellToPathMappings,
          buildTargetValue,
          compilerOutputPathsValue,
          compilerParameters,
          postprocessClassesCommands,
          abiJarParameters,
          libraryJarParameters,
          steps,
          buildableContext,
          withDownwardApi,
          buildCellRootPath,
          resolvedJavac,
          extraParams);
    }

    if (jarParameters != null) {
      addJarCreationSteps(compilerParameters, steps, buildableContext, jarParameters);
    }
  }

  /** Returns Compiler Setup steps */
  public ImmutableList<IsolatedStep> getCompilerSetupIsolatedSteps(
      ImmutableMap<RelPath, RelPath> resourcesMap,
      CompilerOutputPaths outputPaths,
      boolean emptySources) {
    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.

    Builder<IsolatedStep> steps = ImmutableList.builder();

    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getClassesDir()));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getAnnotationPath()));
    steps.add(MkdirIsolatedStep.of(outputPaths.getOutputJarDirPath()));

    // If there are resources, then link them to the appropriate place in the classes directory.
    steps.addAll(CopyResourcesStep.of(resourcesMap));

    if (!emptySources) {
      steps.add(MkdirIsolatedStep.of(outputPaths.getPathToSourcesList().getParent()));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getWorkingDirectory()));
    }

    return steps.build();
  }

  protected void addJarSetupSteps(JarParameters jarParameters, Builder<IsolatedStep> steps) {
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(jarParameters.getJarPath().getParent()));
  }

  protected void recordDepFileIfNecessary(
      CompilerOutputPathsValue compilerOutputPathsValue,
      BuildTargetValue buildTargetValue,
      CompilerParameters compilerParameters,
      BuildableContext buildableContext) {
    if (compilerParameters.shouldTrackClassUsage()) {
      CompilerOutputPaths outputPath =
          compilerOutputPathsValue.getByType(buildTargetValue.getType());
      RelPath depFilePath = CompilerOutputPaths.getDepFilePath(outputPath.getOutputJarDirPath());
      buildableContext.recordArtifact(depFilePath.getPath());
    }
  }

  void addJarCreationSteps(
      CompilerParameters compilerParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      JarParameters jarParameters) {
    // No source files, only resources
    if (compilerParameters.getSourceFilePaths().isEmpty()) {
      steps.add(new JarDirectoryStep(jarParameters));
    }
    buildableContext.recordArtifact(jarParameters.getJarPath().getPath());
  }

  protected void createCompileToJarStepImpl(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue target,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi,
      AbsPath buildCellRootPath,
      ResolvedJavac resolvedJavac,
      T extraParams) {
    Preconditions.checkArgument(abiJarParameters == null);
    Preconditions.checkArgument(
        libraryJarParameters != null
            && libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputPaths().getClassesDir()));

    AbsPath rootPath = getRootPath(filesystemParams);

    createCompileStep(
        filesystemParams,
        cellToPathMappings,
        target,
        compilerOutputPathsValue,
        compilerParameters,
        steps,
        buildableContext,
        resolvedJavac,
        extraParams);

    steps.addAll(
        addPostprocessClassesCommands(
            rootPath,
            postprocessClassesCommands,
            compilerParameters.getOutputPaths().getClassesDir(),
            compilerParameters.getClasspathEntries(),
            getBootClasspath(),
            withDownwardApi,
            buildCellRootPath));

    steps.add(new JarDirectoryStep(libraryJarParameters));
  }

  /**
   * This can be used make the bootclasspath if available, to the postprocess classes commands.
   *
   * @return the bootclasspath.
   */
  protected Optional<String> getBootClasspath() {
    return Optional.empty();
  }

  /**
   * Adds a BashStep for each postprocessClasses command that runs the command followed by the
   * outputDirectory of javac outputs.
   *
   * <p>The expectation is that the command will inspect and update the directory by modifying,
   * adding, and deleting the .class files in the directory.
   *
   * <p>The outputDirectory should be a valid java root. I.e., if outputDirectory is
   * buck-out/bin/java/abc/lib__abc__classes/, then a contained class abc.AbcModule should be at
   * buck-out/bin/java/abc/lib__abc__classes/abc/AbcModule.class
   *
   * @param postprocessClassesCommands the list of commands to post-process .class files.
   * @param outputDirectory the directory that will contain all the javac output.
   * @param declaredClasspathEntries the list of classpath entries.
   * @param bootClasspath the compilation boot classpath.
   */
  @VisibleForTesting
  static ImmutableList<IsolatedStep> addPostprocessClassesCommands(
      AbsPath rootPath,
      List<String> postprocessClassesCommands,
      RelPath outputDirectory,
      ImmutableSortedSet<RelPath> declaredClasspathEntries,
      Optional<String> bootClasspath,
      boolean withDownwardApi,
      AbsPath buildCellRootPath) {
    if (postprocessClassesCommands.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<IsolatedStep> commands = new ImmutableList.Builder<>();
    ImmutableMap<String, String> envVars =
        getEnvs(rootPath, declaredClasspathEntries, bootClasspath);

    RelPath cellPath = ProjectFilesystemUtils.relativize(rootPath, buildCellRootPath);
    for (String postprocessClassesCommand : postprocessClassesCommands) {
      String bashCommand = postprocessClassesCommand + " " + outputDirectory;
      commands.add(
          new BashStep(rootPath, cellPath, withDownwardApi, bashCommand) {

            @Override
            public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
              return envVars;
            }
          });
    }
    return commands.build();
  }

  private static ImmutableMap<String, String> getEnvs(
      AbsPath rootPath,
      ImmutableSortedSet<RelPath> declaredClasspathEntries,
      Optional<String> bootClasspath) {
    ImmutableMap.Builder<String, String> envVarBuilder = ImmutableMap.builder();
    envVarBuilder.put(
        "COMPILATION_CLASSPATH",
        Joiner.on(':').join(Iterables.transform(declaredClasspathEntries, rootPath::resolve)));

    bootClasspath.ifPresent(s -> envVarBuilder.put("COMPILATION_BOOTCLASSPATH", s));
    return envVarBuilder.build();
  }

  public abstract void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      T extraParams);

  public boolean hasAnnotationProcessing() {
    return hasAnnotationProcessing;
  }

  protected static boolean hasAnnotationProcessing(JavacOptions javacOptions) {
    return !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
  }

  public boolean supportsCompilationDaemon() {
    return false;
  }

  /** Returns an extra params type. */
  @SuppressWarnings("unchecked")
  public Class<T> getExtraParamsType() {
    if (supportsCompilationDaemon()) {
      return (Class<T>) JavaExtraParams.class;
    }
    return (Class<T>) BuildContextAwareExtraParams.class;
  }

  /**
   * Extra params marker interface.
   *
   * <p>{@link CompileToJarStepFactory} has 4 subclasses for each JVM language : Java, Kotlin, Scala
   * and Groovy. Want to get rid of {@link com.facebook.buck.core.build.context.BuildContext}
   * parameter for Java implementation (required for JavaCD) and that is why {@link ExtraParams} has
   * been introduced with 2 implementations:
   *
   * <ul>
   *   <li>BuildContextAwareExtraParams (used for Kotlin, Scala and Groovy)
   *   <li>JavaExtraParams (used for Java)
   * </ul>
   *
   * Eventually we would need to get rid of BuildContext for other JVM languages.
   */
  public interface ExtraParams {}

  protected AbsPath getRootPath(FilesystemParams filesystemParams) {
    return AbsPath.get(filesystemParams.getRootPath().getPath());
  }
}
