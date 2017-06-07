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

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.HumanReadableException;

public final class JavacOptionsFactory {
  public static JavacOptions create(
      JavacOptions defaultOptions,
      BuildRuleParams params,
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

    if (jvmLibraryArg.getGenerateAbiFromSource().isPresent()
        && !jvmLibraryArg.getGenerateAbiFromSource().get()) {
      // This parameter can only be used to turn off ABI generation from source where it would
      // otherwise be employed.
      builder.setCompilationMode(JavacCompilationMode.FULL);
    }

    builder.addAllExtraArguments(jvmLibraryArg.getExtraArguments());

    builder.addAllClassesToRemoveFromJar(jvmLibraryArg.getRemoveClasses());

    AnnotationProcessingParams annotationParams =
        jvmLibraryArg.buildAnnotationProcessingParams(
            params.getBuildTarget(),
            params.getProjectFilesystem(),
            resolver,
            defaultOptions.getSafeAnnotationProcessors());
    builder.setAnnotationProcessingParams(annotationParams);

    return builder.build();
  }

  private JavacOptionsFactory() {}
}
