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
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJavacOptions implements RuleKeyAppendable {

  protected abstract Optional<Path> getJavacPath();
  protected abstract Optional<SourcePath> getJavacJarPath();

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

  public abstract List<String> getExtraArguments();
  protected abstract Optional<String> getBootclasspath();
  protected abstract Map<String, String> getSourceToBootclasspath();

  protected boolean isDebug() {
    return !isProductionBuild();
  }

  @Value.Lazy
  public Javac getJavac() {
    Optional<Path> externalJavac = getJavacPath();
    if (externalJavac.isPresent()) {
      return ExternalJavac.createJavac(externalJavac.get());
    }

    Optional<SourcePath> javacJarPath = getJavacJarPath();
    if (javacJarPath.isPresent()) {
      return new JarBackedJavac(
          "com.sun.tools.javac.api.JavacTool",
          ImmutableSet.of(javacJarPath.get()));
    }

    return new JdkProvidedInMemoryJavac();
  }

  public void appendOptionsToList(
      ImmutableList.Builder<String> optionsBuilder,
      final Function<Path, Path> pathRelativizer) {

    // Add some standard options.
    optionsBuilder.add("-source", getSourceLevel());
    optionsBuilder.add("-target", getTargetLevel());

    // Set the sourcepath to stop us reading source files out of jars by mistake.
    optionsBuilder.add("-sourcepath", "");

    if (isDebug()) {
      optionsBuilder.add("-g");
    }

    if (isVerbose()) {
      optionsBuilder.add("-verbose");
    }

    // Override the bootclasspath if Buck is building Java code for Android.
    if (getBootclasspath().isPresent()) {
      optionsBuilder.add("-bootclasspath", getBootclasspath().get());
    } else {
      String bcp = getSourceToBootclasspath().get(getSourceLevel());
      if (bcp != null) {
        optionsBuilder.add("-bootclasspath", bcp);
      }
    }

    // Add annotation processors.
    if (!getAnnotationProcessingParams().isEmpty()) {

      // Specify where to generate sources so IntelliJ can pick them up.
      Path generateTo = getAnnotationProcessingParams().getGeneratedSourceFolderName();
      if (generateTo != null) {
        optionsBuilder.add("-s").add(pathRelativizer.apply(generateTo).toString());
      }

      // Specify processorpath to search for processors.
      optionsBuilder.add("-processorpath",
          Joiner.on(File.pathSeparator).join(
              FluentIterable.from(getAnnotationProcessingParams().getSearchPathElements())
                  .transform(pathRelativizer)
                  .transform(Functions.toStringFunction())));

      // Specify names of processors.
      if (!getAnnotationProcessingParams().getNames().isEmpty()) {
        optionsBuilder.add(
            "-processor",
            Joiner.on(',').join(getAnnotationProcessingParams().getNames()));
      }

      // Add processor parameters.
      for (String parameter : getAnnotationProcessingParams().getParameters()) {
        optionsBuilder.add("-A" + parameter);
      }

      if (getAnnotationProcessingParams().getProcessOnly()) {
        optionsBuilder.add("-proc:only");
      }
    }

    // Add extra arguments.
    optionsBuilder.addAll(getExtraArguments());
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("sourceLevel", getSourceLevel())
        .setReflectively("targetLevel", getTargetLevel())
        .setReflectively("extraArguments", Joiner.on(',').join(getExtraArguments()))
        .setReflectively("debug", isDebug())
        .setReflectively("bootclasspath", getBootclasspath())
        .setReflectively("javac", getJavac())
        .setReflectively("annotationProcessingParams", getAnnotationProcessingParams());

    return builder;
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
    Preconditions.checkNotNull(options);

    JavacOptions.Builder builder = JavacOptions.builder();

    builder.setVerbose(options.isVerbose());
    builder.setProductionBuild(options.isProductionBuild());

    builder.setJavacPath(options.getJavacPath());
    builder.setJavacJarPath(options.getJavacJarPath());
    builder.setAnnotationProcessingParams(options.getAnnotationProcessingParams());
    builder.putAllSourceToBootclasspath(options.getSourceToBootclasspath());
    builder.setBootclasspath(options.getBootclasspath());
    builder.setSourceLevel(options.getSourceLevel());
    builder.setTargetLevel(options.getTargetLevel());
    builder.addAllExtraArguments(options.getExtraArguments());

    return builder;
  }

  public final Optional<Path> getGeneratedSourceFolderName() {
    return fromNullable(getAnnotationProcessingParams().getGeneratedSourceFolderName());
  }
}
