/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.Javac.SRC_ZIP;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.CopyStep.DirectoryMode;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class KotlincToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {

  @AddToRuleKey private final Kotlinc kotlinc;
  @AddToRuleKey private final ImmutableList<String> extraKotlincArguments;
  @AddToRuleKey private final AnnotationProcessingTool annotationProcessingTool;
  @AddToRuleKey private final ExtraClasspathProvider extraClassPath;
  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private final JavacOptions javacOptions;

  private static final String COMPILER_BUILTINS = "-Xadd-compiler-builtins";
  private static final String LOAD_BUILTINS_FROM = "-Xload-builtins-from-dependencies";
  private static final String PLUGIN = "-P";
  private static final String APT_MODE = "aptMode=";
  private static final String X_PLUGIN_ARG = "-Xplugin=";
  private static final String KAPT3_PLUGIN = "plugin:org.jetbrains.kotlin.kapt3:";
  private static final String AP_CLASSPATH_ARG = KAPT3_PLUGIN + "apclasspath=";
  // output path for generated sources;
  private static final String SOURCES_ARG = KAPT3_PLUGIN + "sources=";
  private static final String CLASSES_ARG = KAPT3_PLUGIN + "classes=";
  private static final String INCREMENTAL_ARG = KAPT3_PLUGIN + "incrementalData=";
  // output path for java stubs;
  private static final String STUBS_ARG = KAPT3_PLUGIN + "stubs=";
  private static final String LIGHT_ANALYSIS = KAPT3_PLUGIN + "useLightAnalysis=";
  private static final String CORRECT_ERROR_TYPES = KAPT3_PLUGIN + "correctErrorTypes=";
  private static final String VERBOSE_ARG = KAPT3_PLUGIN + "verbose=";
  private static final String JAVAC_ARG = KAPT3_PLUGIN + "javacArguments=";
  private static final String AP_OPTIONS = KAPT3_PLUGIN + "apoptions=";
  private static final String KAPT_GENERATED = "kapt.kotlin.generated";
  private static final String MODULE_NAME = "-module-name";
  private static final String NO_STDLIB = "-no-stdlib";
  private static final String NO_REFLECT = "-no-reflect";
  private static final String VERBOSE = "-verbose";

  private final ImmutableSortedSet<Path> kotlinHomeLibraries;

  KotlincToJarStepFactory(
      Kotlinc kotlinc,
      ImmutableSortedSet<Path> kotlinHomeLibraries,
      ImmutableList<String> extraKotlincArguments,
      AnnotationProcessingTool annotationProcessingTool,
      ExtraClasspathProvider extraClassPath,
      Javac javac,
      JavacOptions javacOptions) {
    this.kotlinc = kotlinc;
    this.kotlinHomeLibraries = kotlinHomeLibraries;
    this.extraKotlincArguments = extraKotlincArguments;
    this.annotationProcessingTool = annotationProcessingTool;
    this.extraClassPath = extraClassPath;
    this.javac = javac;
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
  }

  @Override
  public void createCompileStep(
      BuildContext buildContext,
      ProjectFilesystem projectFilesystem,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {

    ImmutableSortedSet<Path> declaredClasspathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<Path> sourceFilePaths = parameters.getSourceFilePaths();
    Path outputDirectory = parameters.getOutputPaths().getClassesDir();
    Path pathToSrcsList = parameters.getOutputPaths().getPathToSourcesList();

    Path stubsOutput =
        BuildTargetPaths.getAnnotationPath(projectFilesystem, invokingRule, "__%s_stubs__");
    Path sourcesOutput =
        BuildTargetPaths.getAnnotationPath(projectFilesystem, invokingRule, "__%s_sources__");
    Path classesOutput =
        BuildTargetPaths.getAnnotationPath(projectFilesystem, invokingRule, "__%s_classes__");
    Path kaptGeneratedOutput =
        BuildTargetPaths.getAnnotationPath(
            projectFilesystem, invokingRule, "__%s_kapt_generated__");
    Path incrementalDataOutput =
        BuildTargetPaths.getAnnotationPath(
            projectFilesystem, invokingRule, "__%s_incremental_data__");
    Path tmpFolder =
        BuildTargetPaths.getScratchPath(projectFilesystem, invokingRule, "__%s_gen_sources__");
    Path genOutputFolder =
        BuildTargetPaths.getGenPath(projectFilesystem, invokingRule, "__%s_gen_sources__");
    Path genOutput =
        BuildTargetPaths.getGenPath(
            projectFilesystem, invokingRule, "__%s_gen_sources__/generated" + SRC_ZIP);
    boolean generatingCode = !javacOptions.getAnnotationProcessingParams().isEmpty();

    ImmutableSortedSet.Builder<Path> sourceBuilder =
        ImmutableSortedSet.<Path>naturalOrder().addAll(sourceFilePaths);

    // Only invoke kotlinc if we have kotlin files.
    if (sourceFilePaths.stream().anyMatch(PathMatchers.KOTLIN_PATH_MATCHER::matches)) {
      ImmutableSortedSet<Path> sourcePaths =
          ImmutableSortedSet.<Path>naturalOrder().add(genOutput).addAll(sourceFilePaths).build();

      // Javac requires that the root directory for generated sources already exist.
      addCreateFolderStep(steps, projectFilesystem, buildContext, stubsOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, classesOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, kaptGeneratedOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, incrementalDataOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, sourcesOutput);
      addCreateFolderStep(steps, projectFilesystem, buildContext, tmpFolder);
      addCreateFolderStep(steps, projectFilesystem, buildContext, genOutputFolder);

      ImmutableSortedSet<Path> allClasspaths =
          ImmutableSortedSet.<Path>naturalOrder()
              .addAll(
                  Optional.ofNullable(extraClassPath.getExtraClasspath())
                      .orElse(ImmutableList.of()))
              .addAll(declaredClasspathEntries)
              .addAll(kotlinHomeLibraries)
              .build();

      if (generatingCode && annotationProcessingTool.equals(AnnotationProcessingTool.KAPT)) {
        addKaptGenFolderStep(
            invokingRule,
            steps,
            outputDirectory,
            projectFilesystem,
            sourceFilePaths,
            pathToSrcsList,
            allClasspaths,
            extraKotlincArguments,
            kaptGeneratedOutput,
            stubsOutput,
            incrementalDataOutput,
            classesOutput,
            sourcesOutput,
            parameters.getOutputPaths().getWorkingDirectory(),
            buildContext.getSourcePathResolver());

        sourceBuilder.add(genOutput);
      }

      steps.add(
          CopyStep.forDirectory(
              projectFilesystem, sourcesOutput, tmpFolder, DirectoryMode.CONTENTS_ONLY));
      steps.add(
          CopyStep.forDirectory(
              projectFilesystem, classesOutput, tmpFolder, DirectoryMode.CONTENTS_ONLY));
      steps.add(
          CopyStep.forDirectory(
              projectFilesystem, kaptGeneratedOutput, tmpFolder, DirectoryMode.CONTENTS_ONLY));

      steps.add(
          new ZipStep(
              projectFilesystem,
              genOutput,
              ImmutableSet.of(),
              false,
              ZipCompressionLevel.DEFAULT,
              tmpFolder));

      steps.add(
          new KotlincStep(
              invokingRule,
              outputDirectory,
              sourcePaths,
              pathToSrcsList,
              allClasspaths,
              kotlinc,
              ImmutableList.<String>builder()
                  .addAll(extraKotlincArguments)
                  .add(NO_STDLIB)
                  .add(NO_REFLECT)
                  .add(COMPILER_BUILTINS)
                  .add(LOAD_BUILTINS_FROM)
                  .add(VERBOSE)
                  .add()
                  .build(),
              projectFilesystem,
              Optional.of(parameters.getOutputPaths().getWorkingDirectory())));
    }

    final JavacOptions finalJavacOptions;

    switch (annotationProcessingTool) {
      case KAPT:
        finalJavacOptions =
            javacOptions.withAnnotationProcessingParams(AnnotationProcessingParams.EMPTY);
        break;

      case JAVAC:
        finalJavacOptions = javacOptions;
        break;

      default:
        throw new IllegalStateException(
            "Unexpected annotationProcessingTool " + annotationProcessingTool);
    }

    ImmutableSortedSet<Path> sources = sourceBuilder.build();

    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.copyOf(
            sources
                .stream()
                .filter(input -> !PathMatchers.KOTLIN_PATH_MATCHER.matches(input))
                .collect(Collectors.toSet()));

    CompilerParameters javacParameters =
        CompilerParameters.builder()
            .from(parameters)
            .setClasspathEntries(
                ImmutableSortedSet.<Path>naturalOrder()
                    .add(projectFilesystem.resolve(outputDirectory))
                    .addAll(
                        Optional.ofNullable(extraClassPath.getExtraClasspath())
                            .orElse(ImmutableList.of()))
                    .addAll(declaredClasspathEntries)
                    .build())
            .setSourceFilePaths(javaSourceFiles)
            .build();

    new JavacToJarStepFactory(javac, finalJavacOptions, extraClassPath)
        .createCompileStep(
            buildContext,
            projectFilesystem,
            invokingRule,
            javacParameters,
            steps,
            buildableContext);
  }

  private void addKaptGenFolderStep(
      BuildTarget invokingRule,
      ImmutableList.Builder<Step> steps,
      Path outputDirectory,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> sourceFilePaths,
      Path pathToSrcsList,
      Iterable<? extends Path> declaredClasspathEntries,
      ImmutableList<String> extraArguments,
      Path kaptGenerated,
      Path stubsOutput,
      Path incrementalData,
      Path classesOutput,
      Path sourcesOutput,
      Path workingDirectory,
      SourcePathResolver resolver) {

    ImmutableList<String> pluginFields =
        ImmutableList.copyOf(
            javacOptions
                .getAnnotationProcessingParams()
                .getModernProcessors()
                .stream()
                .map(
                    resolvedJavacPluginProperties ->
                        resolvedJavacPluginProperties.getJavacPluginJsr199Fields(
                            resolver, filesystem))
                .map(JavacPluginJsr199Fields::getClasspath)
                .flatMap(List::stream)
                .map(url -> AP_CLASSPATH_ARG + url.getFile())
                .collect(Collectors.toList()));

    ImmutableList<String> kaptPluginOptions =
        ImmutableList.<String>builder()
            .add(AP_CLASSPATH_ARG + kotlinc.getAnnotationProcessorPath())
            .add(AP_CLASSPATH_ARG + kotlinc.getStdlibPath())
            .addAll(pluginFields)
            .add(SOURCES_ARG + filesystem.resolve(sourcesOutput))
            .add(CLASSES_ARG + filesystem.resolve(classesOutput))
            .add(INCREMENTAL_ARG + filesystem.resolve(incrementalData))
            .add(STUBS_ARG + filesystem.resolve(stubsOutput))
            .add(
                AP_OPTIONS
                    + encodeOptions(
                        Collections.singletonMap(
                            KAPT_GENERATED, filesystem.resolve(kaptGenerated).toString())))
            .add(JAVAC_ARG + encodeOptions(Collections.emptyMap()))
            .add(LIGHT_ANALYSIS + "true") // TODO: Provide value as argument
            .add(CORRECT_ERROR_TYPES + "false") // TODO: Provide value as argument
            .add(VERBOSE_ARG + "true") // TODO: Provide value as argument
            .build();
    String join = Joiner.on(",").join(kaptPluginOptions);

    // First generate java stubs
    steps.add(
        new KotlincStep(
            invokingRule,
            outputDirectory,
            sourceFilePaths,
            pathToSrcsList,
            ImmutableSortedSet.<Path>naturalOrder()
                .add(kotlinc.getStdlibPath())
                .addAll(
                    Optional.ofNullable(extraClassPath.getExtraClasspath())
                        .orElse(ImmutableList.of()))
                .addAll(declaredClasspathEntries)
                .build(),
            kotlinc,
            ImmutableList.<String>builder()
                .addAll(extraArguments)
                .add(MODULE_NAME)
                .add(invokingRule.getShortNameAndFlavorPostfix())
                .add(COMPILER_BUILTINS)
                .add(LOAD_BUILTINS_FROM)
                .add(PLUGIN)
                .add(KAPT3_PLUGIN + APT_MODE + "stubs," + join)
                .add(X_PLUGIN_ARG + kotlinc.getAnnotationProcessorPath())
                .build(),
            filesystem,
            Optional.of(workingDirectory)));

    // Then run the annotation processor
    steps.add(
        new KotlincStep(
            invokingRule,
            outputDirectory,
            sourceFilePaths,
            pathToSrcsList,
            ImmutableSortedSet.<Path>naturalOrder()
                .add(kotlinc.getStdlibPath())
                .addAll(
                    Optional.ofNullable(extraClassPath.getExtraClasspath())
                        .orElse(ImmutableList.of()))
                .addAll(declaredClasspathEntries)
                .build(),
            kotlinc,
            ImmutableList.<String>builder()
                .addAll(extraArguments)
                .add(MODULE_NAME)
                .add(invokingRule.getShortNameAndFlavorPostfix())
                .add(COMPILER_BUILTINS)
                .add(LOAD_BUILTINS_FROM)
                .add(PLUGIN)
                .add(KAPT3_PLUGIN + APT_MODE + "apt," + join)
                .add(X_PLUGIN_ARG + kotlinc.getAnnotationProcessorPath())
                .build(),
            filesystem,
            Optional.of(workingDirectory)));
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    return javacOptions.withBootclasspathFromContext(extraClassPath).getBootclasspath();
  }

  private void addCreateFolderStep(
      ImmutableList.Builder<Step> steps,
      ProjectFilesystem filesystem,
      BuildContext buildContext,
      Path location) {
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, location)));
  }

  private String encodeOptions(Map<String, String> options) {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os)) {

      oos.writeInt(options.size());
      for (Map.Entry<String, String> entry : options.entrySet()) {
        oos.writeUTF(entry.getKey());
        oos.writeUTF(entry.getValue());
      }

      oos.flush();
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
