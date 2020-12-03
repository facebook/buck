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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Provides a base implementation for post compile steps. */
public abstract class CompileToJarStepFactory<T extends CompileToJarStepFactory.ExtraParams>
    implements AddsToRuleKey {

  @AddToRuleKey protected final JavacOptions javacOptions;
  @AddToRuleKey protected final boolean withDownwardApi;

  protected CompileToJarStepFactory(JavacOptions javacOptions, boolean withDownwardApi) {
    this.javacOptions = javacOptions;
    this.withDownwardApi = withDownwardApi;
  }

  public final void createCompileToJarStep(
      FilesystemParams filesystemParams,
      BuildTarget target,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi,
      ImmutableMap<String, RelPath> cellToPathMappings,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      Path buildCellRootPath,
      ResolvedJavac resolvedJavac,
      T extraParams) {
    Preconditions.checkArgument(libraryJarParameters != null || abiJarParameters == null);

    AbsPath rootPath = filesystemParams.getRootPath();
    BaseBuckPaths buckPaths = filesystemParams.getBaseBuckPaths();

    steps.addAll(getCompilerSetupIsolatedSteps(resourcesMap, rootPath, compilerParameters));

    JarParameters jarParameters =
        abiJarParameters != null ? abiJarParameters : libraryJarParameters;
    if (jarParameters != null) {
      addJarSetupSteps(jarParameters, steps);
    }

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    BuildTargetValue buildTargetValue = BuildTargetValue.of(target, buckPaths);
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(buildTargetValue, compilerParameters, buildableContext, buckPaths);

      // This adds the javac command, along with any supporting commands.
      createCompileToJarStepImpl(
          filesystemParams,
          cellToPathMappings,
          target,
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
  protected ImmutableList<IsolatedStep> getCompilerSetupIsolatedSteps(
      ImmutableMap<RelPath, RelPath> resourcesMap,
      AbsPath rootCellRoot,
      CompilerParameters compilerParameters) {
    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    CompilerOutputPaths outputPaths = compilerParameters.getOutputPaths();

    Builder<IsolatedStep> steps = ImmutableList.builder();

    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getClassesDir()));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getAnnotationPath()));
    steps.add(MkdirIsolatedStep.of(getRelPath(rootCellRoot, outputPaths.getOutputJarDirPath())));

    // If there are resources, then link them to the appropriate place in the classes directory.
    steps.addAll(CopyResourcesStep.of(resourcesMap));

    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      steps.add(
          MkdirIsolatedStep.of(
              getRelPath(rootCellRoot, outputPaths.getPathToSourcesList().getParent())));
      steps.addAll(
          MakeCleanDirectoryIsolatedStep.of(
              getRelPath(rootCellRoot, outputPaths.getWorkingDirectory())));
    }

    return steps.build();
  }

  private RelPath getRelPath(AbsPath rootCellRoot, Path path) {
    return rootCellRoot.relativize(rootCellRoot.resolve(path));
  }

  protected void addJarSetupSteps(JarParameters jarParameters, Builder<IsolatedStep> steps) {
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(jarParameters.getJarPath().getParent()));
  }

  protected void recordDepFileIfNecessary(
      BuildTargetValue buildTargetValue,
      CompilerParameters compilerParameters,
      BuildableContext buildableContext,
      BaseBuckPaths buckPaths) {
    if (compilerParameters.shouldTrackClassUsage()) {
      Path depFilePath = CompilerOutputPaths.getDepFilePath(buildTargetValue, buckPaths);
      buildableContext.recordArtifact(depFilePath);
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
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget target,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi,
      Path buildCellRootPath,
      ResolvedJavac resolvedJavac,
      T extraParams) {
    Preconditions.checkArgument(abiJarParameters == null);
    Preconditions.checkArgument(
        libraryJarParameters != null
            && libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputPaths().getClassesDir()));

    AbsPath rootPath = filesystemParams.getRootPath();

    createCompileStep(
        filesystemParams,
        cellToPathMappings,
        target,
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
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Optional<String> bootClasspath,
      boolean withDownwardApi,
      Path buildCellRootPath) {
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
      ImmutableSortedSet<Path> declaredClasspathEntries,
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
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      T extraParams);

  boolean hasAnnotationProcessing() {
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
  protected interface ExtraParams {}
}
