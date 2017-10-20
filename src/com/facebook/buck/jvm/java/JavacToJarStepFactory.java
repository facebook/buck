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

import static com.facebook.buck.jvm.java.AbstractJavacOptions.SpoolMode;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class JavacToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {
  private static final Logger LOG = Logger.get(JavacToJarStepFactory.class);

  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private JavacOptions javacOptions;
  @AddToRuleKey private final ExtraClasspathFromContextFunction extraClasspathFromContextFunction;

  public JavacToJarStepFactory(
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      Javac javac,
      JavacOptions javacOptions,
      ExtraClasspathFromContextFunction extraClasspathFromContextFunction) {
    super(resolver, ruleFinder, projectFilesystem);
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.extraClasspathFromContextFunction = extraClasspathFromContextFunction;
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {
    final JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathFromContextFunction, context);

    boolean generatingCode = !javacOptions.getAnnotationProcessingParams().isEmpty();
    if (generatingCode) {
      // Javac requires that the root directory for generated sources already exist.
      addAnnotationGenFolderStep(
          parameters.getGeneratedCodeDirectory(),
          projectFilesystem,
          steps,
          buildableContext,
          context);
    }

    final ClassUsageFileWriter usedClassesFileWriter =
        parameters.shouldTrackClassUsage()
            ? new DefaultClassUsageFileWriter(parameters.getDepFilePath())
            : NoOpClassUsageFileWriter.instance();
    steps.add(
        new JavacStep(
            usedClassesFileWriter,
            javac,
            buildTimeOptions,
            invokingRule,
            resolver,
            projectFilesystem,
            new ClasspathChecker(),
            parameters,
            null,
            null));
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathFromContextFunction, context);
    return buildTimeOptions.getBootclasspath();
  }

  @Override
  public Tool getCompiler() {
    return javac;
  }

  @Override
  public Iterable<BuildRule> getExtraDeps(SourcePathRuleFinder ruleFinder) {
    // If any dep of an annotation processor changes, we need to recompile, so we add those as
    // extra deps
    return Iterables.concat(
        super.getExtraDeps(ruleFinder),
        ruleFinder.filterBuildRuleInputs(javacOptions.getAnnotationProcessingParams().getInputs()));
  }

  @Override
  public void createCompileToJarStepImpl(
      BuildContext context,
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {
    Preconditions.checkArgument(
        libraryJarParameters == null
            || libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputDirectory()));

    String spoolMode = javacOptions.getSpoolMode().name();
    // In order to use direct spooling to the Jar:
    // (1) It must be enabled through a .buckconfig.
    // (2) The target must have 0 postprocessing steps.
    // (3) Tha compile API must be JSR 199.
    boolean isSpoolingToJarEnabled =
        compilerParameters.getAbiGenerationMode().isSourceAbi()
            || (postprocessClassesCommands.isEmpty()
                && javacOptions.getSpoolMode() == AbstractJavacOptions.SpoolMode.DIRECT_TO_JAR
                && javac instanceof Jsr199Javac);

    LOG.info(
        "Target: %s SpoolMode: %s Expected SpoolMode: %s Postprocessing steps: %s",
        invokingRule.getBaseName(),
        (isSpoolingToJarEnabled) ? (SpoolMode.DIRECT_TO_JAR) : (SpoolMode.INTERMEDIATE_TO_DISK),
        spoolMode,
        postprocessClassesCommands.toString());

    if (isSpoolingToJarEnabled) {
      final JavacOptions buildTimeOptions =
          javacOptions.withBootclasspathFromContext(extraClasspathFromContextFunction, context);
      boolean generatingCode = !buildTimeOptions.getAnnotationProcessingParams().isEmpty();
      if (generatingCode) {
        // Javac requires that the root directory for generated sources already exists.
        addAnnotationGenFolderStep(
            compilerParameters.getGeneratedCodeDirectory(),
            projectFilesystem,
            steps,
            buildableContext,
            context);
      }

      final ClassUsageFileWriter usedClassesFileWriter =
          compilerParameters.shouldTrackClassUsage()
              ? new DefaultClassUsageFileWriter(compilerParameters.getDepFilePath())
              : NoOpClassUsageFileWriter.instance();
      steps.add(
          new JavacStep(
              usedClassesFileWriter,
              javac,
              buildTimeOptions,
              invokingRule,
              resolver,
              projectFilesystem,
              new ClasspathChecker(),
              compilerParameters,
              abiJarParameters,
              libraryJarParameters));
    } else {
      super.createCompileToJarStepImpl(
          context,
          invokingRule,
          compilerParameters,
          postprocessClassesCommands,
          abiJarParameters,
          libraryJarParameters,
          steps,
          buildableContext);
    }
  }

  private static void addAnnotationGenFolderStep(
      Path annotationGenFolder,
      ProjectFilesystem filesystem,
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, annotationGenFolder)));
    buildableContext.recordArtifact(annotationGenFolder);
  }

  @VisibleForTesting
  public JavacOptions getJavacOptions() {
    return javacOptions;
  }
}
