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
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
public class JavacOptions {

  private static final String DEFAULT_JAVA_TARGET = "6";

  // Fields are initialized in order. We need the default java target level to have been set.
  public static final JavacOptions DEFAULTS = JavacOptions.builder().build();

  private final boolean debug;
  private final boolean verbose;
  private final String sourceLevel;
  private final String targetLevel;
  private final AnnotationProcessingData annotationProcessingData;
  private final Optional<String> bootclasspath;

  private JavacOptions(boolean debug,
      boolean verbose,
      String sourceLevel,
      String targetLevel,
      Optional<String> bootclasspath,
      AnnotationProcessingData annotationProcessingData) {
    this.debug = debug;
    this.verbose = verbose;
    this.sourceLevel = Preconditions.checkNotNull(sourceLevel);
    this.targetLevel = Preconditions.checkNotNull(targetLevel);
    this.bootclasspath = Preconditions.checkNotNull(bootclasspath);
    this.annotationProcessingData = Preconditions.checkNotNull(annotationProcessingData);
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
    Preconditions.checkNotNull(optionsBuilder);

    // Add some standard options.
    optionsBuilder.add("-target", sourceLevel);
    optionsBuilder.add("-source", targetLevel);

    if (debug) {
      optionsBuilder.add("-g");
    }

    if (verbose) {
      optionsBuilder.add("-verbose");
    }

    // Override the bootclasspath if Buck is building Java code for Android.
    if (bootclasspath.isPresent()) {
      optionsBuilder.add("-bootclasspath", bootclasspath.get());
    }

    // Add annotation processors.
    AnnotationProcessingData annotationProcessingData =
        decorator.decorate(this.annotationProcessingData);
    if (!annotationProcessingData.isEmpty()) {

      // Specify where to generate sources so IntelliJ can pick them up.
      Path generateTo = annotationProcessingData.getGeneratedSourceFolderName();
      if (generateTo != null) {
        optionsBuilder.add("-s").add(generateTo.toString());
      }

      // Create a path relativizer that relativizes all processor paths, except for
      // AbiWritingAnnotationProcessingDataDecorator.ABI_PROCESSOR_CLASSPATH, which will already be
      // an absolute path.
      Function<Path, Path> pathRelativizerThatOmitsAbiProcessor =
          new Function<Path, Path>() {
        @Override
        public Path apply(Path searchPathElement) {
          if (AbiWritingAnnotationProcessingDataDecorator.ABI_PROCESSOR_CLASSPATH.equals(
              searchPathElement)) {
            return searchPathElement;
          } else {
            return pathRelativizer.apply(searchPathElement);
          }
        }
      };

      // Specify processorpath to search for processors.
      optionsBuilder.add("-processorpath",
          Joiner.on(':').join(
              FluentIterable.from(annotationProcessingData.getSearchPathElements())
                .transform(pathRelativizerThatOmitsAbiProcessor)
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
  }

  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    // TODO(simons): Include bootclasspath params.
    builder.set("sourceLevel", sourceLevel)
        .set("targetLevel", targetLevel)
        .set("debug", debug);

    return annotationProcessingData.appendToRuleKey(builder);
  }

  public AnnotationProcessingData getAnnotationProcessingData() {
    return annotationProcessingData;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(JavacOptions options) {
    Builder builder = builder();

    builder.setVerboseOutput(options.verbose);
    if (!options.debug) {
      builder.setProductionBuild();
    }

    builder.setSourceLevel(options.sourceLevel);
    builder.setTargetLevel(options.targetLevel);

    builder.setAnnotationProcessingData(options.annotationProcessingData);
    builder.setBootclasspath(options.bootclasspath.orNull());

    return builder;
  }

  public static class Builder {
    private boolean debug = true;
    private boolean verbose = false;
    private String sourceLevel = DEFAULT_JAVA_TARGET;
    private String targetLevel = DEFAULT_JAVA_TARGET;
    private Optional<String> bootclasspath = Optional.absent();
    private AnnotationProcessingData annotationProcessingData = AnnotationProcessingData.EMPTY;

    private Builder() {
    }

    public Builder setProductionBuild() {
      debug = false;
      return this;
    }

    public Builder setVerboseOutput(boolean verbose) {
      this.verbose = verbose;
      return this;
    }

    public Builder setSourceLevel(String sourceLevel) {
      this.sourceLevel = Preconditions.checkNotNull(sourceLevel);
      return this;
    }

    public Builder setTargetLevel(String targetLevel) {
      this.targetLevel = Preconditions.checkNotNull(targetLevel);
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

    public JavacOptions build() {
      return new JavacOptions(
          debug,
          verbose,
          sourceLevel,
          targetLevel,
          bootclasspath,
          annotationProcessingData
      );
    }
  }
}
