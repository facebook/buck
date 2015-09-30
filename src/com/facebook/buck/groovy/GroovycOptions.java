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

package com.facebook.buck.groovy;

import com.facebook.buck.java.AnnotationProcessingParams;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import javax.annotation.Nullable;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
public class GroovycOptions {

  private final boolean debug;
  private final boolean verbose;
  private final AnnotationProcessingParams annotationProcessingParams;
  private final ImmutableList<String> extraArguments;
  private final Optional<String> bootclasspath;
  private final ImmutableMap<String, String> sourceToBootclasspath;

  private GroovycOptions(
      boolean debug,
      boolean verbose,
      ImmutableList<String> extraArguments,
      Optional<String> bootclasspath,
      ImmutableMap<String, String> sourceToBootclasspath,
      AnnotationProcessingParams annotationProcessingParams) {
    this.debug = debug;
    this.verbose = verbose;
    this.extraArguments = extraArguments;
    this.bootclasspath = bootclasspath;
    this.sourceToBootclasspath = sourceToBootclasspath;
    this.annotationProcessingParams = annotationProcessingParams;
  }

  public ImmutableList<String> getExtraArguments() {
    return extraArguments;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(GroovycOptions options) {
    Preconditions.checkNotNull(options);

    Builder builder = new Builder();

    builder.setVerboseOutput(options.verbose);
    if (!options.debug) {
      builder.setProductionBuild();
    }

    builder.setAnnotationProcessingParams(options.annotationProcessingParams);
    builder.sourceToBootclasspath = options.sourceToBootclasspath;
    builder.setBootclasspath(options.bootclasspath.orNull());
    builder.setExtraArguments(options.getExtraArguments());

    return builder;
  }

  public static class Builder {
    private ImmutableList<String> extraArguments = ImmutableList.of();
    private boolean debug = true;
    private boolean verbose = false;
    private Optional<String> bootclasspath = Optional.absent();
    private AnnotationProcessingParams annotationProcessingParams =
        AnnotationProcessingParams.EMPTY;
    private ImmutableMap<String, String> sourceToBootclasspath;

    private Builder() {
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

    public Builder setBootclasspath(@Nullable String bootclasspath) {
      this.bootclasspath = Optional.fromNullable(bootclasspath);
      return this;
    }

    public Builder setAnnotationProcessingParams(
        AnnotationProcessingParams annotationProcessingParams) {
      this.annotationProcessingParams = annotationProcessingParams;
      return this;
    }

    public GroovycOptions build() {
      return new GroovycOptions(
          debug,
          verbose,
          extraArguments,
          bootclasspath,
          sourceToBootclasspath,
          annotationProcessingParams);
    }
  }
}
