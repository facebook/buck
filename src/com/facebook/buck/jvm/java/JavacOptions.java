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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.javacd.model.BuildJavaCommand.SpoolMode;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
@BuckStyleValueWithBuilder
public abstract class JavacOptions implements AddsToRuleKey {

  @Value.Default
  @AddToRuleKey
  public SpoolMode getSpoolMode() {
    return SpoolMode.INTERMEDIATE_TO_DISK;
  }

  @Value.Default
  @AddToRuleKey
  protected boolean isProductionBuild() {
    return false;
  }

  @Value.Default
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  protected boolean isVerbose() {
    return false;
  }

  @Value.Default
  @AddToRuleKey
  public JavacLanguageLevelOptions getLanguageLevelOptions() {
    return JavacLanguageLevelOptions.DEFAULT;
  }

  @Value.Default
  @AddToRuleKey
  public JavacPluginParams getJavaAnnotationProcessorParams() {
    return JavacPluginParams.EMPTY;
  }

  @Value.Default
  @AddToRuleKey
  public JavacPluginParams getStandardJavacPluginParams() {
    return JavacPluginParams.EMPTY;
  }

  @AddToRuleKey
  public abstract List<String> getExtraArguments();

  // TODO(cjhopman): This should use SourcePaths
  @AddToRuleKey
  public abstract Optional<String> getBootclasspath();

  // TODO(cjhopman): This should be resolved to the appropriate source.
  // TODO(cjhopman): Should this be added to the rulekey?
  @CustomFieldBehavior({DefaultFieldInputs.class, DefaultFieldSerialization.class})
  protected abstract Map<String, ImmutableList<PathSourcePath>> getSourceToBootclasspath();

  @AddToRuleKey
  protected boolean isDebug() {
    return !isProductionBuild();
  }

  @Value.Default
  @AddToRuleKey
  public boolean trackClassUsage() {
    return false;
  }

  @Value.Default
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  public boolean trackJavacPhaseEvents() {
    return false;
  }

  public JavacOptions withBootclasspathFromContext(ExtraClasspathProvider extraClasspathProvider) {
    String extraClasspath =
        Joiner.on(File.pathSeparator).join(extraClasspathProvider.getExtraClasspath());
    JavacOptions options = this;

    if (!extraClasspath.isEmpty()) {
      return options.withBootclasspath(extraClasspath);
    }

    return options;
  }

  public JavacOptions withExtraArguments(ImmutableList<String> extraArguments) {
    if (getExtraArguments().equals(extraArguments)) {
      return this;
    }
    return builder().from(this).setExtraArguments(extraArguments).build();
  }

  public JavacOptions withLanguageLevelOptions(JavacLanguageLevelOptions languageLevelOptions) {
    if (getLanguageLevelOptions().equals(languageLevelOptions)) {
      return this;
    }
    return builder().from(this).setLanguageLevelOptions(languageLevelOptions).build();
  }

  public JavacOptions withBootclasspath(@Nullable String bootclasspath) {
    if (getBootclasspath().equals(Optional.ofNullable(bootclasspath))) {
      return this;
    }
    return builder().from(this).setBootclasspath(bootclasspath).build();
  }

  public JavacOptions withJavaAnnotationProcessorParams(
      JavacPluginParams javaAnnotationProcessorParams) {
    if (getJavaAnnotationProcessorParams().equals(javaAnnotationProcessorParams)) {
      return this;
    }
    return builder()
        .from(this)
        .setJavaAnnotationProcessorParams(javaAnnotationProcessorParams)
        .build();
  }

  /**
   * @return a list of {@code SourcePaths} for a particular source level extracted from {@link
   *     #getSourceToBootclasspath } or empty list if bootclasspath for a this source level is not
   *     defined.
   */
  public ImmutableList<SourcePath> getSourceLevelBootclasspath() {
    ImmutableList<PathSourcePath> bootclasspath =
        getSourceToBootclasspath().get(getLanguageLevelOptions().getSourceLevel());

    if (bootclasspath != null) {
      return ImmutableList.copyOf(bootclasspath); // upcast to ImmutableList<SourcePath>
    } else {
      return ImmutableList.of();
    }
  }

  public void appendOptionsTo(
      OptionsConsumer optionsConsumer, SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    ImmutableList<PathSourcePath> bootclasspath =
        getSourceToBootclasspath().get(getLanguageLevelOptions().getSourceLevel());
    Optional<List<RelPath>> bootclasspathList = Optional.empty();
    if (bootclasspath != null) {
      bootclasspathList =
          Optional.of(
              bootclasspath.stream()
                  .map(resolver::getAbsolutePath)
                  .map(ruleCellRoot::relativize)
                  .collect(Collectors.toList()));
    }

    appendOptionsTo(
        ruleCellRoot,
        optionsConsumer,
        getBootclasspathString(getBootclasspath(), bootclasspathList),
        getLanguageLevelOptions(),
        isDebug(),
        isVerbose(),
        getJavaAnnotationProcessorParams(),
        getStandardJavacPluginParams(),
        getExtraArguments());
  }

  /** Add options method */
  public static void appendOptionsTo(
      OptionsConsumer optionsConsumer,
      ResolvedJavacOptions resolvedJavacOptions,
      AbsPath rootCellRoot) {

    appendOptionsTo(
        rootCellRoot,
        optionsConsumer,
        getBootclasspathString(
            resolvedJavacOptions.getBootclasspath(), resolvedJavacOptions.getBootclasspathList()),
        resolvedJavacOptions.getLanguageLevelOptions(),
        resolvedJavacOptions.isDebug(),
        resolvedJavacOptions.isVerbose(),
        resolvedJavacOptions.getJavaAnnotationProcessorParams(),
        resolvedJavacOptions.getStandardJavacPluginParams(),
        resolvedJavacOptions.getExtraArguments());
  }

  private static void appendOptionsTo(
      AbsPath ruleCellRoot,
      OptionsConsumer optionsConsumer,
      String bootclasspathString,
      JavacLanguageLevelOptions languageLevelOptions,
      boolean isDebug,
      boolean isVerbose,
      JavacPluginParams javaAnnotationProcessorParams,
      JavacPluginParams standardJavacPluginParams,
      List<String> extraArguments) {

    // Add some standard options.
    String sourceLevel = languageLevelOptions.getSourceLevel();
    optionsConsumer.addOptionValue("source", sourceLevel);
    optionsConsumer.addOptionValue("target", languageLevelOptions.getTargetLevel());

    // Set the sourcepath to stop us reading source files out of jars by mistake.
    optionsConsumer.addOptionValue("sourcepath", "");

    if (isDebug) {
      optionsConsumer.addFlag("g");
    }

    if (isVerbose) {
      optionsConsumer.addFlag("verbose");
    }

    // Override the bootclasspath if Buck is building Java code for Android.
    if (bootclasspathString != null) {
      optionsConsumer.addOptionValue("bootclasspath", bootclasspathString);
    }

    ImmutableList.Builder<ResolvedJavacPluginProperties> allPluginsBuilder =
        ImmutableList.builder();
    // Add annotation processors.
    if (!javaAnnotationProcessorParams.isEmpty()) {
      ImmutableList<ResolvedJavacPluginProperties> annotationProcessors =
          javaAnnotationProcessorParams.getPluginProperties();
      allPluginsBuilder.addAll(annotationProcessors);

      // Specify names of processors.
      optionsConsumer.addOptionValue(
          "processor",
          annotationProcessors.stream()
              .map(ResolvedJavacPluginProperties::getProcessorNames)
              .flatMap(Collection::stream)
              .collect(Collectors.joining(",")));

      // Add processor parameters.
      for (String parameter : javaAnnotationProcessorParams.getParameters()) {
        optionsConsumer.addFlag("A" + parameter);
      }

      if (javaAnnotationProcessorParams.getProcessOnly()) {
        optionsConsumer.addFlag("proc:only");
      }
    } else {
      // Disable automatic annotation processor lookup
      optionsConsumer.addFlag("proc:none");
    }

    if (!standardJavacPluginParams.isEmpty()) {
      ImmutableList<ResolvedJavacPluginProperties> javacPlugins =
          standardJavacPluginParams.getPluginProperties();
      allPluginsBuilder.addAll(javacPlugins);

      for (ResolvedJavacPluginProperties properties : javacPlugins) {
        optionsConsumer.addFlag("Xplugin:" + properties.getProcessorNames().first());

        // Add plugin's SourcePath params with RelPath's resolved relative to root
        for (Map.Entry<String, RelPath> sourcePathParam : properties.getPathParams().entrySet()) {
          optionsConsumer.addFlag(
              String.format(
                  "A%s=%s",
                  sourcePathParam.getKey(), ruleCellRoot.resolve(sourcePathParam.getValue())));
        }
      }

      // Add plugin parameters.
      optionsConsumer.addExtras(standardJavacPluginParams.getParameters());
    }

    // Specify classpath to include javac plugins and annotation processors.
    ImmutableList<ResolvedJavacPluginProperties> allPlugins = allPluginsBuilder.build();
    if (!allPlugins.isEmpty()) {
      optionsConsumer.addOptionValue(
          "processorpath",
          ResolvedJavacPluginProperties.getJoinedClasspath(allPlugins, ruleCellRoot));
    }

    // Add extra arguments.
    optionsConsumer.addExtras(extraArguments);
  }

  private static String getBootclasspathString(
      Optional<String> bootclasspathOptional, Optional<List<RelPath>> bootclasspathList) {
    if (bootclasspathOptional.isPresent()) {
      return bootclasspathOptional.get();
    }

    if (bootclasspathList.isPresent()) {
      return bootclasspathList.get().stream()
          .map(RelPath::toString)
          .collect(Collectors.joining(File.pathSeparator));
    }

    return null;
  }

  static JavacOptions.Builder builderForUseInJavaBuckConfig() {
    return JavacOptions.builder();
  }

  public static JavacOptions.Builder builder(JavacOptions options) {
    Builder builder = builder();

    return builder.from(options);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableJavacOptions.Builder {}
}
