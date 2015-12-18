/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavacArg {
  public Optional<String> source;
  public Optional<String> target;
  public Optional<String> javaVersion;
  public Optional<Path> javac;
  public Optional<SourcePath> javacJar;
  public Optional<Either<BuiltInJavac, SourcePath>> compiler;
  public Optional<ImmutableList<String>> extraArguments;
  public Optional<ImmutableSortedSet<BuildTarget>> annotationProcessorDeps;
  public Optional<ImmutableList<String>> annotationProcessorParams;
  public Optional<ImmutableSet<String>> annotationProcessors;
  public Optional<Boolean> annotationProcessorOnly;

  public AnnotationProcessingParams buildAnnotationProcessingParams(
      BuildTarget owner,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver) {
    ImmutableSet<String> annotationProcessors =
        this.annotationProcessors.or(ImmutableSet.<String>of());

    if (annotationProcessors.isEmpty()) {
      return AnnotationProcessingParams.EMPTY;
    }

    AnnotationProcessingParams.Builder builder = new AnnotationProcessingParams.Builder();
    builder.setOwnerTarget(owner);
    builder.addAllProcessors(annotationProcessors);
    builder.setProjectFilesystem(filesystem);
    ImmutableSortedSet<BuildRule> processorDeps =
        resolver.getAllRules(annotationProcessorDeps.or(ImmutableSortedSet.<BuildTarget>of()));
    for (BuildRule processorDep : processorDeps) {
      builder.addProcessorBuildTarget(processorDep);
    }
    for (String processorParam : annotationProcessorParams.or(ImmutableList.<String>of())) {
      builder.addParameter(processorParam);
    }
    builder.setProcessOnly(annotationProcessorOnly.or(Boolean.FALSE));

    return builder.build();
  }
}
