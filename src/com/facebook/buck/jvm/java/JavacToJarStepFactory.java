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
import com.facebook.buck.io.ProjectFilesystem;
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class JavacToJarStepFactory extends BaseCompileToJarStepFactory implements AddsToRuleKey {
  private static final Logger LOG = Logger.get(JavacToJarStepFactory.class);

  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private JavacOptions javacOptions;
  @AddToRuleKey private final JavacOptionsAmender amender;
  @Nullable private Path abiJar;

  public JavacToJarStepFactory(
      Javac javac, JavacOptions javacOptions, JavacOptionsAmender amender) {
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.amender = amender;
  }

  public void setCompileAbi(Path abiJar) {
    this.abiJar = abiJar;
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Path outputDirectory,
      Optional<Path> generatedCodeDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> depFilePath,
      Path pathToSrcsList,
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {

    final JavacOptions buildTimeOptions = amender.amend(javacOptions, context);

    boolean generatingCode = !javacOptions.getAnnotationProcessingParams().isEmpty();
    if (generatingCode) {
      // Javac requires that the root directory for generated sources already exist.
      addAnnotationGenFolderStep(
          generatedCodeDirectory, filesystem, steps, buildableContext, context);
    }

    final ClassUsageFileWriter usedClassesFileWriter =
        depFilePath.isPresent()
            ? new DefaultClassUsageFileWriter(depFilePath.get())
            : NoOpClassUsageFileWriter.instance();
    steps.add(
        new JavacStep(
            outputDirectory,
            usedClassesFileWriter,
            generatingCode ? generatedCodeDirectory : Optional.empty(),
            workingDirectory,
            sourceFilePaths,
            pathToSrcsList,
            declaredClasspathEntries,
            javac,
            buildTimeOptions,
            invokingRule,
            resolver,
            filesystem,
            new ClasspathChecker(),
            Optional.empty(),
            abiJar));
  }

  @Override
  public void createJarStep(
      ProjectFilesystem filesystem,
      Path outputDirectory,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      RemoveClassesPatternsMatcher classesToRemoveFromJar,
      Path outputJar,
      ImmutableList.Builder<Step> steps) {
    steps.add(
        new JarDirectoryStep(
            filesystem,
            outputJar,
            ImmutableSortedSet.of(outputDirectory),
            mainClass.orElse(null),
            manifestFile.orElse(null),
            true,
            javacOptions.getCompilationMode() == JavacCompilationMode.ABI,
            classesToRemoveFromJar::shouldRemoveClass));
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    JavacOptions buildTimeOptions = amender.amend(javacOptions, context);
    return buildTimeOptions.getBootclasspath();
  }

  @Override
  protected Tool getCompiler() {
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
  public void createCompileToJarStep(
      BuildContext context,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Path outputDirectory,
      Optional<Path> generatedCodeDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> depFilePath,
      Path pathToSrcsList,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<Path> entriesToJar,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      Path outputJar,
      /* output params */
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext,
      RemoveClassesPatternsMatcher classesToRemoveFromJar) {

    String spoolMode = javacOptions.getSpoolMode().name();
    // In order to use direct spooling to the Jar:
    // (1) It must be enabled through a .buckconfig.
    // (2) The target must have 0 postprocessing steps.
    // (3) Tha compile API must be JSR 199.
    boolean isSpoolingToJarEnabled =
        postprocessClassesCommands.isEmpty()
            && javacOptions.getSpoolMode() == AbstractJavacOptions.SpoolMode.DIRECT_TO_JAR
            && javac instanceof Jsr199Javac;

    LOG.info(
        "Target: %s SpoolMode: %s Expected SpoolMode: %s Postprocessing steps: %s",
        invokingRule.getBaseName(),
        (isSpoolingToJarEnabled) ? (SpoolMode.DIRECT_TO_JAR) : (SpoolMode.INTERMEDIATE_TO_DISK),
        spoolMode,
        postprocessClassesCommands.toString());

    if (isSpoolingToJarEnabled) {
      final JavacOptions buildTimeOptions = amender.amend(javacOptions, context);
      // Javac requires that the root directory for generated sources already exists.
      addAnnotationGenFolderStep(
          generatedCodeDirectory, filesystem, steps, buildableContext, context);

      final ClassUsageFileWriter usedClassesFileWriter =
          depFilePath.isPresent()
              ? new DefaultClassUsageFileWriter(depFilePath.get())
              : NoOpClassUsageFileWriter.instance();
      steps.add(
          new JavacStep(
              outputDirectory,
              usedClassesFileWriter,
              generatedCodeDirectory,
              workingDirectory,
              sourceFilePaths,
              pathToSrcsList,
              declaredClasspathEntries,
              javac,
              buildTimeOptions,
              invokingRule,
              resolver,
              filesystem,
              new ClasspathChecker(),
              Optional.of(
                  DirectToJarOutputSettings.of(
                      outputJar, classesToRemoveFromJar, entriesToJar, mainClass, manifestFile)),
              abiJar));
    } else {
      super.createCompileToJarStep(
          context,
          sourceFilePaths,
          invokingRule,
          resolver,
          ruleFinder,
          filesystem,
          declaredClasspathEntries,
          outputDirectory,
          generatedCodeDirectory,
          workingDirectory,
          depFilePath,
          pathToSrcsList,
          postprocessClassesCommands,
          entriesToJar,
          mainClass,
          manifestFile,
          outputJar,
          steps,
          buildableContext,
          classesToRemoveFromJar);
    }
  }

  private static void addAnnotationGenFolderStep(
      Optional<Path> annotationGenFolder,
      ProjectFilesystem filesystem,
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    if (annotationGenFolder.isPresent()) {
      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), filesystem, annotationGenFolder.get())));
      buildableContext.recordArtifact(annotationGenFolder.get());
    }
  }

  @VisibleForTesting
  public JavacOptions getJavacOptions() {
    return javacOptions;
  }
}
