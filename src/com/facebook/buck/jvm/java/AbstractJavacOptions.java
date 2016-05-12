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

import static com.google.common.base.Optional.fromNullable;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJavacOptions implements RuleKeyAppendable {

  /**
   * The method in which the compiler output is spooled.
   */
  public enum SpoolMode {
    /**
     * Writes the compiler output directly to a .jar file while retaining the intermediate .class
     * files in memory.
     * If {@link com.facebook.buck.jvm.java.JavaLibraryDescription.Arg} postprocessClassesCommands
     * are present, the builder will resort to writing .class files to disk by necessity.
     */
    DIRECT_TO_JAR,

    /**
     * Writes the intermediate .class files from the compiler output to disk which is later packed
     * up into a .jar file.
     */
    INTERMEDIATE_TO_DISK,
  }

  public enum JavacSource {
    /** Shell out to the javac in the JDK */
    EXTERNAL,
    /** Run javac in-process, loading it from a jar specified in .buckconfig. */
    JAR,
    /** Run javac in-process, loading it from the JRE in which Buck is running. */
    JDK,
  }

  protected abstract Optional<Path> getJavacPath();
  protected abstract Optional<SourcePath> getJavacJarPath();

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

  public abstract String getSourceLevel();
  @VisibleForTesting
  abstract String getTargetLevel();

  @Value.Default
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return AnnotationProcessingParams.EMPTY;
  }

  public abstract Set<String> getSafeAnnotationProcessors();

  public abstract List<String> getExtraArguments();
  protected abstract Optional<String> getBootclasspath();
  protected abstract Map<String, String> getSourceToBootclasspath();

  protected boolean isDebug() {
    return !isProductionBuild();
  }

  @Value.Default
  boolean getTrackClassUsageNotDisabled() {
    return true;
  }

  public boolean trackClassUsage() {
    final JavacSource javacSource = getJavacSource();
    return getTrackClassUsageNotDisabled() &&
        (javacSource == JavacSource.JAR || javacSource == JavacSource.JDK);
  }

  public JavacSource getJavacSource() {
    if (getJavacPath().isPresent()) {
      return JavacSource.EXTERNAL;
    } else if (getJavacJarPath().isPresent()) {
      return JavacSource.JAR;
    } else {
      return JavacSource.JDK;
    }
  }

  @Value.Lazy
  public Javac getJavac() {
    final JavacSource javacSource = getJavacSource();
    switch (javacSource) {
      case EXTERNAL:
        return ExternalJavac.createJavac(getJavacPath().get());
      case JAR:
        return new JarBackedJavac(
            "com.sun.tools.javac.api.JavacTool",
            ImmutableSet.of(getJavacJarPath().get()));
      case JDK:
        return new JdkProvidedInMemoryJavac();
    }
    throw new AssertionError("Unknown javac source: " + javacSource);
  }

  public void appendOptionsTo(
      OptionsConsumer optionsConsumer,
      final Function<Path, Path> pathRelativizer) {

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
    if (!getAnnotationProcessingParams().isEmpty()) {
      // Specify where to generate sources so IntelliJ can pick them up.
      Path generateTo = getAnnotationProcessingParams().getGeneratedSourceFolderName();
      if (generateTo != null) {
        //noinspection ConstantConditions
        optionsConsumer.addOptionValue("s", pathRelativizer.apply(generateTo).toString());
      }

      // Specify processorpath to search for processors.
      optionsConsumer.addOptionValue("processorpath",
          Joiner.on(File.pathSeparator).join(
              FluentIterable.from(getAnnotationProcessingParams().getSearchPathElements())
                  .transform(pathRelativizer)
                  .transform(Functions.toStringFunction())));

      // Specify names of processors.
      if (!getAnnotationProcessingParams().getNames().isEmpty()) {
        optionsConsumer.addOptionValue(
            "processor",
            Joiner.on(',').join(getAnnotationProcessingParams().getNames()));
      }

      // Add processor parameters.
      for (String parameter : getAnnotationProcessingParams().getParameters()) {
        optionsConsumer.addFlag("A" + parameter);
      }

      if (getAnnotationProcessingParams().getProcessOnly()) {
        optionsConsumer.addFlag("proc:only");
      }
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
        .setReflectively("javac", getJavac())
        .setReflectively("annotationProcessingParams", getAnnotationProcessingParams())
        .setReflectively("spoolMode", getSpoolMode())
        .setReflectively("trackClassUsage", trackClassUsage());
  }

  public ImmutableSortedSet<SourcePath> getInputs(SourcePathResolver resolver) {
    ImmutableSortedSet.Builder<SourcePath> builder = ImmutableSortedSet.<SourcePath>naturalOrder()
        .addAll(getAnnotationProcessingParams().getInputs());

    Optional<SourcePath> javacJarPath = getJavacJarPath();
    if (javacJarPath.isPresent()) {
      SourcePath sourcePath = javacJarPath.get();

      // Add the original rule regardless of what happens next.
      builder.add(sourcePath);

      Optional<BuildRule> possibleRule = resolver.getRule(sourcePath);

      if (possibleRule.isPresent()) {
        BuildRule rule = possibleRule.get();

        // And now include any transitive deps that contribute to the classpath.
        if (rule instanceof JavaLibrary) {
          builder.addAll(
              FluentIterable.from(((JavaLibrary) rule).getDepsForTransitiveClasspathEntries())
                  .transform(SourcePaths.getToBuildTargetSourcePath())
                  .toList());
        } else {
          builder.add(sourcePath);
        }
      }
    }

    return builder.build();
  }

  static JavacOptions.Builder builderForUseInJavaBuckConfig() {
    return JavacOptions.builder();
  }

  public static JavacOptions.Builder builder(JavacOptions options) {
    JavacOptions.Builder builder = JavacOptions.builder();

    return builder.from(options);
  }

  public final Optional<Path> getGeneratedSourceFolderName() {
    return fromNullable(getAnnotationProcessingParams().getGeneratedSourceFolderName());
  }
}
