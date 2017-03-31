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
    if ((jvmLibraryArg.source.isPresent() || jvmLibraryArg.target.isPresent()) &&
        jvmLibraryArg.javaVersion.isPresent()) {
      throw new HumanReadableException("Please set either source and target or java_version.");
    }

    JavacOptions.Builder builder = JavacOptions.builder(defaultOptions);

    if (jvmLibraryArg.javaVersion.isPresent()) {
      builder.setSourceLevel(jvmLibraryArg.javaVersion.get());
      builder.setTargetLevel(jvmLibraryArg.javaVersion.get());
    }

    if (jvmLibraryArg.source.isPresent()) {
      builder.setSourceLevel(jvmLibraryArg.source.get());
    }

    if (jvmLibraryArg.target.isPresent()) {
      builder.setTargetLevel(jvmLibraryArg.target.get());
    }

    if (jvmLibraryArg.generateAbiFromSource.isPresent() &&
        !jvmLibraryArg.generateAbiFromSource.get()) {
      // This parameter can only be used to turn off ABI generation from source where it would
      // otherwise be employed.
      builder.setCompilationMode(Javac.CompilationMode.FULL);
    }

    builder.addAllExtraArguments(jvmLibraryArg.extraArguments);

    builder.addAllClassesToRemoveFromJar(jvmLibraryArg.removeClasses);

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
