/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.DefaultFieldInputs;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractJavacOptions implements AddsToRuleKey {

  /** The method in which the compiler output is spooled. */
  public enum SpoolMode {
    /**
     * Writes the compiler output directly to a .jar file while retaining the intermediate .class
     * files in memory. If {@link
     * com.facebook.buck.jvm.java.JavaLibraryDescription.AbstractJavaLibraryDescriptionArg}
     * postprocessClassesCommands are present, the builder will resort to writing .class files to
     * disk by necessity.
     */
    DIRECT_TO_JAR,

    /**
     * Writes the intermediate .class files from the compiler output to disk which is later packed
     * up into a .jar file.
     */
    INTERMEDIATE_TO_DISK,
  }

  @Value.Default
  @AddToRuleKey
  protected SpoolMode getSpoolMode() {
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
  protected abstract Optional<String> getBootclasspath();

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
  protected boolean trackJavacPhaseEvents() {
    return false;
  }

  public void validateOptions(Function<String, Boolean> classpathChecker) throws IOException {
    if (getBootclasspath().isPresent()) {
      String bootclasspath = getBootclasspath().get();
      try {
        if (!classpathChecker.apply(bootclasspath)) {
          throw new IOException(
              String.format("Bootstrap classpath %s contains no valid entries", bootclasspath));
        }
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
  }

  public JavacOptions withBootclasspathFromContext(ExtraClasspathProvider extraClasspathProvider) {
    String extraClasspath =
        Joiner.on(File.pathSeparator).join(extraClasspathProvider.getExtraClasspath());
    JavacOptions options = (JavacOptions) this;

    if (!extraClasspath.isEmpty()) {
      return options.withBootclasspath(extraClasspath);
    }

    return options;
  }

  public void appendOptionsTo(
      OptionsConsumer optionsConsumer,
      SourcePathResolver pathResolver,
      ProjectFilesystem filesystem) {

    // Add some standard options.
    optionsConsumer.addOptionValue("source", getLanguageLevelOptions().getSourceLevel());
    optionsConsumer.addOptionValue("target", getLanguageLevelOptions().getTargetLevel());

    // Set the sourcepath to stop us reading source files out of jars by mistake.
    optionsConsumer.addOptionValue("sourcepath", "");

    if (isDebug()) {
      optionsConsumer.addFlag("g");
    }

    if (isVerbose()) {
      optionsConsumer.addFlag("verbose");
    }

    // Override the bootclasspath if Buck is building Java code for Android.
    if (getBootclasspath().isPresent()) {
      optionsConsumer.addOptionValue("bootclasspath", getBootclasspath().get());
    } else {
      ImmutableList<PathSourcePath> bootclasspath =
          getSourceToBootclasspath().get(getLanguageLevelOptions().getSourceLevel());
      if (bootclasspath != null) {
        String bcp =
            bootclasspath.stream()
                .map(pathResolver::getAbsolutePath)
                .map(filesystem::relativize)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
        optionsConsumer.addOptionValue("bootclasspath", bcp);
      }
    }

    ImmutableList.Builder<ResolvedJavacPluginProperties> allPluginsBuilder =
        ImmutableList.builder();
    // Add annotation processors.
    JavacPluginParams annotationProcessingParams = getJavaAnnotationProcessorParams();
    if (!annotationProcessingParams.isEmpty()) {
      ImmutableList<ResolvedJavacPluginProperties> annotationProcessors =
          annotationProcessingParams.getPluginProperties();
      allPluginsBuilder.addAll(annotationProcessors);

      // Specify names of processors.
      optionsConsumer.addOptionValue(
          "processor",
          annotationProcessors.stream()
              .map(ResolvedJavacPluginProperties::getProcessorNames)
              .flatMap(Collection::stream)
              .collect(Collectors.joining(",")));

      // Add processor parameters.
      for (String parameter : annotationProcessingParams.getParameters()) {
        optionsConsumer.addFlag("A" + parameter);
      }

      if (annotationProcessingParams.getProcessOnly()) {
        optionsConsumer.addFlag("proc:only");
      }
    } else {
      // Disable automatic annotation processor lookup
      optionsConsumer.addFlag("proc:none");
    }

    JavacPluginParams standardJavacPluginParams = getStandardJavacPluginParams();
    if (!standardJavacPluginParams.isEmpty()) {
      ImmutableList<ResolvedJavacPluginProperties> javacPlugins =
          standardJavacPluginParams.getPluginProperties();
      allPluginsBuilder.addAll(javacPlugins);

      for (ResolvedJavacPluginProperties properties : javacPlugins) {
        optionsConsumer.addFlag("Xplugin:" + properties.getProcessorNames().first());
      }

      // Add plugin parameters.
      optionsConsumer.addExtras(standardJavacPluginParams.getParameters());
    }

    // Specify classpath to include javac plugins and annotation processors.
    ImmutableList<ResolvedJavacPluginProperties> allPlugins = allPluginsBuilder.build();
    if (!allPlugins.isEmpty()) {
      optionsConsumer.addOptionValue(
          "processorpath",
          ResolvedJavacPluginProperties.getJoinedClasspath(pathResolver, filesystem, allPlugins));
    }

    // Add extra arguments.
    optionsConsumer.addExtras(getExtraArguments());
  }

  static JavacOptions.Builder builderForUseInJavaBuckConfig() {
    return JavacOptions.builder();
  }

  public static JavacOptions.Builder builder(JavacOptions options) {
    JavacOptions.Builder builder = JavacOptions.builder();

    return builder.from(options);
  }
}
