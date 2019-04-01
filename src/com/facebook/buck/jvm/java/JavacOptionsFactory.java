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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;

public final class JavacOptionsFactory {
  public static JavacOptions create(
      JavacOptions defaultOptions,
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      JvmLibraryArg jvmLibraryArg) {
    if ((jvmLibraryArg.getSource().isPresent() || jvmLibraryArg.getTarget().isPresent())
        && jvmLibraryArg.getJavaVersion().isPresent()) {
      throw new HumanReadableException("Please set either source and target or java_version.");
    }

    JavacOptions.Builder builder = JavacOptions.builder(defaultOptions);
    JavacLanguageLevelOptions.Builder languageLevelOptionsBuilder =
        JavacLanguageLevelOptions.builder();

    languageLevelOptionsBuilder.setSourceLevel(
        defaultOptions.getLanguageLevelOptions().getSourceLevel());
    languageLevelOptionsBuilder.setTargetLevel(
        defaultOptions.getLanguageLevelOptions().getTargetLevel());

    if (jvmLibraryArg.getJavaVersion().isPresent()) {
      languageLevelOptionsBuilder.setSourceLevel(jvmLibraryArg.getJavaVersion().get());
      languageLevelOptionsBuilder.setTargetLevel(jvmLibraryArg.getJavaVersion().get());
    }

    if (jvmLibraryArg.getSource().isPresent()) {
      languageLevelOptionsBuilder.setSourceLevel(jvmLibraryArg.getSource().get());
    }

    if (jvmLibraryArg.getTarget().isPresent()) {
      languageLevelOptionsBuilder.setTargetLevel(jvmLibraryArg.getTarget().get());
    }

    builder.setLanguageLevelOptions(languageLevelOptionsBuilder.build());

    builder.addAllExtraArguments(jvmLibraryArg.getExtraArguments());

    JavacPluginParams annotationParams =
        jvmLibraryArg.buildJavaAnnotationProcessorParams(buildTarget, resolver);
    builder.setJavaAnnotationProcessorParams(annotationParams);

    JavacPluginParams standardJavacPluginsParams =
        jvmLibraryArg.buildStandardJavacParams(buildTarget, resolver);
    builder.setStandardJavacPluginParams(standardJavacPluginsParams);

    return builder.build();
  }

  private JavacOptionsFactory() {}
}
