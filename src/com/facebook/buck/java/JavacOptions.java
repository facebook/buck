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

package com.facebook.buck.java;

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

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
public abstract class JavacOptions implements RuleKeyAppendable {

  protected abstract Optional<Path> getJavacPath();
  protected abstract Optional<Path> getJavacJarPath();

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
      return new ExternalJavac(externalJavac.get());
    }
    return new Jsr199Javac(getJavacJarPath());
  }

  public void appendOptionsToList(
      ImmutableList.Builder<String> optionsBuilder,
      final Function<Path, Path> pathRelativizer) {

    // Add some standard options.
    optionsBuilder.add("-source", getSourceLevel());
    optionsBuilder.add("-target", getTargetLevel());

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
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder, String key) {
    // TODO(simons): Include bootclasspath params.
    builder.setReflectively(key + ".sourceLevel", getSourceLevel())
        .setReflectively(
            key + ".javacPath",
            getJavacPath().transform(Functions.toStringFunction()).orNull())
        .setReflectively(
            key + ".javacJarPath",
            getJavacJarPath().transform(Functions.toStringFunction()).orNull())
        .setReflectively(key + ".targetLevel", getTargetLevel())
        .setReflectively(key + ".extraArguments", Joiner.on(',').join(getExtraArguments()))
        .setReflectively(key + ".debug", isDebug());

    return getAnnotationProcessingParams().appendToRuleKey(builder, key);
  }

  static ImmutableJavacOptions.Builder builderForUseInJavaBuckConfig() {
    return ImmutableJavacOptions.builder();
  }

  public static ImmutableJavacOptions.Builder builder(JavacOptions options) {
    Preconditions.checkNotNull(options);

    ImmutableJavacOptions.Builder builder = ImmutableJavacOptions.builder();

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
}
