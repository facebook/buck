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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJavacOptions implements RuleKeyAppendable {

  // Default combined source and target level.
  public static final String TARGETED_JAVA_VERSION = "7";

  /** The method in which the compiler output is spooled. */
  public enum SpoolMode {
    /**
     * Writes the compiler output directly to a .jar file while retaining the intermediate .class
     * files in memory. If {@link com.facebook.buck.jvm.java.JavaLibraryDescription.Arg}
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
  protected SpoolMode getSpoolMode() {
    return SpoolMode.INTERMEDIATE_TO_DISK;
  }

  @Value.Default
  protected boolean isProductionBuild() {
    return false;
  }

  @Value.Default
  protected boolean isVerbose() {
    return false;
  }

  @Value.Default
  public String getSourceLevel() {
    return TARGETED_JAVA_VERSION;
  }

  @VisibleForTesting
  @Value.Default
  public String getTargetLevel() {
    return TARGETED_JAVA_VERSION;
  }

  @Value.Default
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return AnnotationProcessingParams.EMPTY;
  }

  public abstract Set<String> getSafeAnnotationProcessors();

  public abstract List<String> getExtraArguments();

  public abstract Set<Pattern> getClassesToRemoveFromJar();

  protected abstract Optional<String> getBootclasspath();

  protected abstract Map<String, String> getSourceToBootclasspath();

  protected boolean isDebug() {
    return !isProductionBuild();
  }

  @Value.Default
  public boolean trackClassUsage() {
    return false;
  }

  @Value.Default
  public JavacCompilationMode getCompilationMode() {
    return JavacCompilationMode.FULL;
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

  public void appendOptionsTo(
      OptionsConsumer optionsConsumer,
      SourcePathResolver pathResolver,
      ProjectFilesystem filesystem) {

    // Add some standard options.
    optionsConsumer.addOptionValue("source", getSourceLevel());
    optionsConsumer.addOptionValue("target", getTargetLevel());

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
      String bcp = getSourceToBootclasspath().get(getSourceLevel());
      if (bcp != null) {
        optionsConsumer.addOptionValue("bootclasspath", bcp);
      }
    }

    // Add annotation processors.
    AnnotationProcessingParams annotationProcessingParams = getAnnotationProcessingParams();
    if (!annotationProcessingParams.isEmpty()) {
      // Specify where to generate sources so IntelliJ can pick them up.
      Path generateTo = annotationProcessingParams.getGeneratedSourceFolderName();
      if (generateTo != null) {
        //noinspection ConstantConditions
        optionsConsumer.addOptionValue("s", filesystem.resolve(generateTo).toString());
      }

      ImmutableList<ResolvedJavacPluginProperties> annotationProcessors =
          annotationProcessingParams.getAnnotationProcessors(filesystem, pathResolver);

      // Specify processorpath to search for processors.
      optionsConsumer.addOptionValue(
          "processorpath",
          annotationProcessors
              .stream()
              .map(ResolvedJavacPluginProperties::getClasspath)
              .flatMap(Arrays::stream)
              .distinct()
              .map(URL::toString)
              .collect(Collectors.joining(File.pathSeparator)));

      // Specify names of processors.
      optionsConsumer.addOptionValue(
          "processor",
          annotationProcessors
              .stream()
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

    // Add extra arguments.
    optionsConsumer.addExtras(getExtraArguments());
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("sourceLevel", getSourceLevel())
        .setReflectively("targetLevel", getTargetLevel())
        .setReflectively("extraArguments", Joiner.on(',').join(getExtraArguments()))
        .setReflectively("debug", isDebug())
        .setReflectively("bootclasspath", getBootclasspath())
        .setReflectively("annotationProcessingParams", getAnnotationProcessingParams())
        .setReflectively("spoolMode", getSpoolMode())
        .setReflectively("trackClassUsage", trackClassUsage())
        .setReflectively("compilationMode", getCompilationMode());
  }

  static JavacOptions.Builder builderForUseInJavaBuckConfig() {
    return JavacOptions.builder();
  }

  public static JavacOptions.Builder builder(JavacOptions options) {
    JavacOptions.Builder builder = JavacOptions.builder();

    return builder.from(options);
  }

  public final Optional<Path> getGeneratedSourceFolderName() {
    return Optional.ofNullable(getAnnotationProcessingParams().getGeneratedSourceFolderName());
  }
}
