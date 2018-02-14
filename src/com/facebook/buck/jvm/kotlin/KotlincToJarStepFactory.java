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

import static javax.xml.bind.DatatypeConverter.printBase64Binary;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSortedSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class KotlincToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {

  private static final PathOrGlobMatcher JAVA_PATH_MATCHER = new PathOrGlobMatcher("**.java");
  private static final PathOrGlobMatcher KOTLIN_PATH_MATCHER = new PathOrGlobMatcher("**.kt");

  @AddToRuleKey private final Kotlinc kotlinc;
  @AddToRuleKey private final ImmutableList<String> extraArguments;
  @AddToRuleKey private final ExtraClasspathProvider extraClassPath;

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

  private final Javac javac;
  private final JavacOptions javacOptions;

  public KotlincToJarStepFactory(
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      Kotlinc kotlinc,
      ImmutableList<String> extraArguments,
      ExtraClasspathProvider extraClassPath,
      Javac javac,
      JavacOptions javacOptions) {
    super(resolver, ruleFinder, projectFilesystem);
    this.kotlinc = kotlinc;
    this.extraArguments = extraArguments;
    this.extraClassPath = extraClassPath;
    this.javac = javac;
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
  }

  @Override
  public void createCompileStep(
      BuildContext buildContext,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {

    ImmutableSortedSet<Path> declaredClasspathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<Path> sourceFilePaths = parameters.getSourceFilePaths();
    Path outputDirectory = parameters.getOutputDirectory();
    Path pathToSrcsList = parameters.getPathToSourcesList();

    Path stubsOutput = BuildTargets.getAnnotationPath(projectFilesystem, invokingRule, "__%s_stubs__");
    Path classesOutput = BuildTargets.getAnnotationPath(projectFilesystem, invokingRule, "__%s_classes__");
    Path kaptGenerated =
        BuildTargets.getAnnotationPath(projectFilesystem, invokingRule, "__%s_kapt_generated");
    Path incrementalData =
        BuildTargets.getAnnotationPath(projectFilesystem, invokingRule, "__%s_incremental_data");
    Path apGenerated =
        CompilerParameters.getAnnotationPath(projectFilesystem, invokingRule).orElse(kaptGenerated);

    // Only invoke kotlinc if we have kotlin files.
    if (sourceFilePaths.stream().anyMatch(KOTLIN_PATH_MATCHER::matches)) {
      ImmutableSortedSet<Path> sourcePaths =
          ImmutableSortedSet.<Path>naturalOrder()
              .add(outputDirectory)
              .add(stubsOutput)
              .add(classesOutput)
              .add(kaptGenerated)
              .add(incrementalData)
              .add(apGenerated)
              .addAll(sourceFilePaths)
              .build();

      // Javac requires that the root directory for generated sources already exist.
      addCreateFolderStep(steps, projectFilesystem, buildableContext, buildContext, stubsOutput);
      addCreateFolderStep(steps, projectFilesystem, buildableContext, buildContext, classesOutput);
      addCreateFolderStep(steps, projectFilesystem, buildableContext, buildContext, kaptGenerated);
      addCreateFolderStep(steps, projectFilesystem, buildableContext, buildContext, incrementalData);
      addCreateFolderStep(steps, projectFilesystem, buildableContext, buildContext, apGenerated);

      boolean generatingCode = !javacOptions.getAnnotationProcessingParams().isEmpty();
      if (generatingCode) {
        addAnnotationGenFolderStep(
            invokingRule,
            steps,
            outputDirectory,
            projectFilesystem,
            sourceFilePaths,
            pathToSrcsList,
            sourcePaths,
            declaredClasspathEntries,
            kaptGenerated,
            stubsOutput,
            incrementalData,
            classesOutput,
            apGenerated,
            resolver);
      }

      steps.add(
          new KotlincStep(
              invokingRule,
              outputDirectory,
              sourcePaths,
              pathToSrcsList,
              ImmutableSortedSet.<Path>naturalOrder()
                  .addAll(
                      Optional.ofNullable(extraClassPath.getExtraClasspath())
                          .orElse(ImmutableList.of()))
                  .addAll(declaredClasspathEntries)
                  .build(),
              kotlinc,
              extraArguments,
              projectFilesystem));
    }

    ImmutableSortedSet<Path> sources =
        ImmutableSortedSet.<Path>naturalOrder()
            .add(outputDirectory)
            .add(stubsOutput)
            .add(classesOutput)
            .add(kaptGenerated)
            .add(incrementalData)
            .add(apGenerated)
            .addAll(sourceFilePaths)
            .build();

    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.copyOf(
            sources.stream().filter(JAVA_PATH_MATCHER::matches).collect(Collectors.toSet()));

    // Only invoke javac if we have java files.
    // Here we never run the annotation processor, kotlinc handles that. There is a special case
    // where if the kotlin code does not contain annotations that need to generate code, we could
    // use the annotation processor for java instead which would perform faster than kapt. This is
    // too difficult to determine from here alone.
    if (!javaSourceFiles.isEmpty()) {
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
      new JavacToJarStepFactory(
              resolver, ruleFinder, projectFilesystem, javac, javacOptions, extraClassPath)
          .createCompileStep(buildContext, invokingRule, javacParameters, steps, buildableContext);
    }
  }

  private void addAnnotationGenFolderStep(
      BuildTarget invokingRule,
      ImmutableList.Builder<Step> steps,
      Path outputDirectory,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> sourceFilePaths,
      Path pathToSrcsList,
      ImmutableSortedSet<Path> sourcePaths,
      Iterable<? extends Path> declaredClasspathEntries,
      Path kaptGenerated,
      Path stubsOutput,
      Path incrementalData,
      Path classesOutput,
      Path apGenerated,
      SourcePathResolver resolver) {

    ImmutableList<String> pluginFields =
        ImmutableList.copyOf(
            javacOptions
                .getAnnotationProcessingParams()
                .getAnnotationProcessors(filesystem, resolver)
                .stream()
                .map(ResolvedJavacPluginProperties::getJavacPluginJsr199Fields)
                .map(JavacPluginJsr199Fields::getClasspath)
                .flatMap(List::stream)
                .map(url -> AP_CLASSPATH_ARG + url.getFile())
                .collect(Collectors.toList()));

    ImmutableList<String> apClassPaths =
        ImmutableList.<String>builder()
            .add(AP_CLASSPATH_ARG + kotlinc.getAPPaths())
            .add(AP_CLASSPATH_ARG + kotlinc.getStdlibPath())
            .addAll(pluginFields)
            .add(SOURCES_ARG + apGenerated)
            .add(CLASSES_ARG + classesOutput)
            .add(INCREMENTAL_ARG + incrementalData)
            .add(STUBS_ARG + stubsOutput)
            .add(
                AP_OPTIONS
                    + encodeOptions(
                        Collections.singletonMap(KAPT_GENERATED, kaptGenerated.toString())))
            .add(JAVAC_ARG + encodeOptions(Collections.emptyMap()))
            .add(LIGHT_ANALYSIS + "true") // TODO: Provide value as argument
            .add(CORRECT_ERROR_TYPES + "false") // TODO: Provide value as argument
            .add(VERBOSE_ARG + "true") // TODO: Provide value as argument
            .build();
    String join = Joiner.on(",").join(apClassPaths);

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
            ImmutableList.of(
                MODULE_NAME,
                invokingRule.getShortNameAndFlavorPostfix(),
                COMPILER_BUILTINS,
                LOAD_BUILTINS_FROM,
                PLUGIN,
                KAPT3_PLUGIN + APT_MODE + "stubs," + join,
                X_PLUGIN_ARG + kotlinc.getAPPaths()),
            filesystem));

    // Then run the annotation processor
    steps.add(
        new KotlincStep(
            invokingRule,
            outputDirectory,
            sourcePaths,
            pathToSrcsList,
            ImmutableSortedSet.<Path>naturalOrder()
                .add(kotlinc.getStdlibPath())
                .addAll(
                    Optional.ofNullable(extraClassPath.getExtraClasspath())
                        .orElse(ImmutableList.of()))
                .addAll(declaredClasspathEntries)
                .build(),
            kotlinc,
            ImmutableList.of(
                MODULE_NAME,
                invokingRule.getShortNameAndFlavorPostfix(),
                COMPILER_BUILTINS,
                LOAD_BUILTINS_FROM,
                PLUGIN,
                KAPT3_PLUGIN + APT_MODE + "apt," + join,
                X_PLUGIN_ARG + kotlinc.getAPPaths()),
            filesystem));
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    return javacOptions.withBootclasspathFromContext(extraClassPath).getBootclasspath();
  }

  @Override
  public Tool getCompiler() {
    return kotlinc;
  }

  private void addCreateFolderStep(
      ImmutableList.Builder<Step> steps,
      ProjectFilesystem filesystem,
      BuildableContext buildableContext,
      BuildContext buildContext,
      Path location) {
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, location)));
    buildableContext.recordArtifact(location);
  }

  private String encodeOptions(Map<String, String> options) {
    try {
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(os);

      oos.writeInt(options.size());
      for (Map.Entry<String, String> entry : options.entrySet()) {
        oos.writeUTF(entry.getKey());
        oos.writeUTF(entry.getValue());
      }

      oos.flush();
      return printBase64Binary(os.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
