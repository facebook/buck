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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.HumanReadableException;

public final class JavacOptionsFactory {
  public static JavacOptions create(
      JavacOptions defaultOptions,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      JvmLibraryArg jvmLibraryArg) {
    if ((jvmLibraryArg.getSource().isPresent() || jvmLibraryArg.getTarget().isPresent())
        && jvmLibraryArg.getJavaVersion().isPresent()) {
      throw new HumanReadableException("Please set either source and target or java_version.");
    }

    JavacOptions.Builder builder = JavacOptions.builder(defaultOptions);

    if (jvmLibraryArg.getJavaVersion().isPresent()) {
      builder.setSourceLevel(jvmLibraryArg.getJavaVersion().get());
      builder.setTargetLevel(jvmLibraryArg.getJavaVersion().get());
    }

    if (jvmLibraryArg.getSource().isPresent()) {
      builder.setSourceLevel(jvmLibraryArg.getSource().get());
    }

    if (jvmLibraryArg.getTarget().isPresent()) {
      builder.setTargetLevel(jvmLibraryArg.getTarget().get());
    }

    builder.addAllExtraArguments(jvmLibraryArg.getExtraArguments());

    AnnotationProcessingParams annotationParams =
        jvmLibraryArg.buildAnnotationProcessingParams(
            buildTarget, projectFilesystem, resolver, defaultOptions.getSafeAnnotationProcessors());
    builder.setAnnotationProcessingParams(annotationParams);

    return builder.build();
  }

  private JavacOptionsFactory() {}
}
