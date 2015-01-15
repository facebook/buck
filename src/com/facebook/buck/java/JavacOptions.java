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

import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.RuleKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
public class JavacOptions {

  private final JavaCompilerEnvironment javacEnv;
  private final boolean debug;
  private final boolean verbose;
  private final String sourceLevel;
  private final String targetLevel;
  private final AnnotationProcessingData annotationProcessingData;
  private final ImmutableList<String> extraArguments;
  private final Optional<String> bootclasspath;
  private final ImmutableMap<String, String> sourceToBootclasspath;

  private JavacOptions(
      JavaCompilerEnvironment javacEnv,
      boolean debug,
      boolean verbose,
      String sourceLevel,
      String targetLevel,
      ImmutableList<String> extraArguments,
      Optional<String> bootclasspath,
      ImmutableMap<String, String> sourceToBootclasspath,
      AnnotationProcessingData annotationProcessingData) {
    this.javacEnv = Preconditions.checkNotNull(javacEnv);
    this.debug = debug;
    this.verbose = verbose;
    this.sourceLevel = sourceLevel;
    this.targetLevel = targetLevel;
    this.extraArguments = extraArguments;
    this.bootclasspath = bootclasspath;
    this.sourceToBootclasspath = sourceToBootclasspath;
    this.annotationProcessingData = annotationProcessingData;
  }

  public JavaCompilerEnvironment getJavaCompilerEnvironment() {
    return javacEnv;
  }

  public void appendOptionsToList(ImmutableList.Builder<String> optionsBuilder,
      Function<Path, Path> pathRelativizer) {
    appendOptionsToList(optionsBuilder,
        pathRelativizer,
        AnnotationProcessingDataDecorators.identity());
  }

  public void appendOptionsToList(ImmutableList.Builder<String> optionsBuilder,
      final Function<Path, Path> pathRelativizer,
      AnnotationProcessingDataDecorator decorator) {

    // Add some standard options.
    optionsBuilder.add("-source", targetLevel);
    optionsBuilder.add("-target", sourceLevel);

    if (debug) {
      optionsBuilder.add("-g");
    }

    if (verbose) {
      optionsBuilder.add("-verbose");
    }

    // Override the bootclasspath if Buck is building Java code for Android.
    if (bootclasspath.isPresent()) {
      optionsBuilder.add("-bootclasspath", bootclasspath.get());
    } else {
      String bcp = sourceToBootclasspath.get(sourceLevel);
      if (bcp != null) {
        optionsBuilder.add("-bootclasspath", bcp);
      }
    }

    // Add annotation processors.
    AnnotationProcessingData annotationProcessingData =
        decorator.decorate(this.annotationProcessingData);
    if (!annotationProcessingData.isEmpty()) {

      // Specify where to generate sources so IntelliJ can pick them up.
      Path generateTo = annotationProcessingData.getGeneratedSourceFolderName();
      if (generateTo != null) {
        optionsBuilder.add("-s").add(pathRelativizer.apply(generateTo).toString());
      }

      // Specify processorpath to search for processors.
      optionsBuilder.add("-processorpath",
          Joiner.on(File.pathSeparator).join(
              FluentIterable.from(annotationProcessingData.getSearchPathElements())
                  .transform(pathRelativizer)
                  .transform(Functions.toStringFunction())));

      // Specify names of processors.
      if (!annotationProcessingData.getNames().isEmpty()) {
        optionsBuilder.add("-processor", Joiner.on(',').join(annotationProcessingData.getNames()));
      }

      // Add processor parameters.
      for (String parameter : annotationProcessingData.getParameters()) {
        optionsBuilder.add("-A" + parameter);
      }

      if (annotationProcessingData.getProcessOnly()) {
        optionsBuilder.add("-proc:only");
      }
    }

    // Add extra arguments.
    optionsBuilder.addAll(extraArguments);
  }

  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // TODO(simons): Include bootclasspath params.
    builder.set("sourceLevel", sourceLevel)
        .set("targetLevel", targetLevel)
        .set("extraArguments", Joiner.on(',').join(extraArguments))
        .set("debug", debug)
        .set("javacVersion", javacEnv.getJavacVersion().transform(
            Functions.toStringFunction()).orNull());

    return annotationProcessingData.appendToRuleKey(builder);
  }

  public AnnotationProcessingData getAnnotationProcessingData() {
    return annotationProcessingData;
  }

  public String getSourceLevel() {
    return sourceLevel;
  }

  @VisibleForTesting
  String getTargetLevel() {
    return targetLevel;
  }

  public ImmutableList<String> getExtraArguments() {
    return extraArguments;
  }

  static Builder builderForUseInJavaBuckConfig() {
    return new Builder();
  }

  public static Builder builder(JavacOptions options) {
    Preconditions.checkNotNull(options);

    Builder builder = new Builder();

    builder.setVerboseOutput(options.verbose);
    if (!options.debug) {
      builder.setProductionBuild();
    }

    builder.setAnnotationProcessingData(options.annotationProcessingData);
    builder.sourceToBootclasspath = options.sourceToBootclasspath;
    builder.setBootclasspath(options.bootclasspath.orNull());
    builder.setSourceLevel(options.getSourceLevel());
    builder.setTargetLevel(options.getTargetLevel());
    builder.setExtraArguments(options.getExtraArguments());

    builder.setJavaCompilerEnvironment(options.getJavaCompilerEnvironment());

    return builder;
  }

  public static class Builder {
    private String sourceLevel;
    private String targetLevel;
    private ImmutableList<String> extraArguments = ImmutableList.of();
    private boolean debug = true;
    private boolean verbose = false;
    private Optional<String> bootclasspath = Optional.absent();
    private AnnotationProcessingData annotationProcessingData = AnnotationProcessingData.EMPTY;
    @Nullable
    private JavaCompilerEnvironment javacEnv;
    private ImmutableMap<String, String> sourceToBootclasspath;

    private Builder() {
    }

    public Builder setSourceLevel(String sourceLevel) {
      this.sourceLevel = Preconditions.checkNotNull(sourceLevel);
      return this;
    }

    public Builder setTargetLevel(String targetLevel) {
      this.targetLevel = Preconditions.checkNotNull(targetLevel);
      return this;
    }

    public Builder setExtraArguments(ImmutableList<String> extraArguments) {
      this.extraArguments = extraArguments;
      return this;
    }

    public Builder setProductionBuild() {
      debug = false;
      return this;
    }

    public Builder setVerboseOutput(boolean verbose) {
      this.verbose = verbose;
      return this;
    }

    public Builder setBootclasspathMap(Map<String, String> sourceVersionToClasspath) {
      this.sourceToBootclasspath = ImmutableMap.copyOf(sourceVersionToClasspath);
      return this;
    }

    public Builder setBootclasspath(@Nullable String bootclasspath) {
      this.bootclasspath = Optional.fromNullable(bootclasspath);
      return this;
    }

    public Builder setAnnotationProcessingData(AnnotationProcessingData annotationProcessingData) {
      this.annotationProcessingData = annotationProcessingData;
      return this;
    }

    public Builder setJavaCompilerEnvironment(JavaCompilerEnvironment javacEnv) {
      this.javacEnv = javacEnv;
      return this;
    }

    public JavacOptions build() {
      return new JavacOptions(
          Preconditions.checkNotNull(javacEnv),
          debug,
          verbose,
          Preconditions.checkNotNull(sourceLevel),
          Preconditions.checkNotNull(targetLevel),
          extraArguments,
          bootclasspath,
          sourceToBootclasspath,
          annotationProcessingData);
    }
  }
}
