/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Set;

/**
 * Creates the {@link Step}s needed to generate an uber {@code R.java} file.
 * <p>
 * Buck builds two types of {@code R.java} files: temporary ones and uber ones. A temporary
 * {@code R.java} file's values are garbage and correspond to a single Android libraries. An uber
 * {@code R.java} file represents the transitive closure of Android libraries that are being
 * packaged into an APK and has the real values for that APK.
 */
public class RDotJava {

  /** Utility class: do not instantiate. */
  private RDotJava() {}

  static JavacStep createJavacStepForUberRDotJavaFiles(
      Set<Path> javaSourceFilePaths,
      Path outputDirectory,
      JavacOptions javacOptions,
      BuildTarget buildTarget) {
    return createJavacStepForDummyRDotJavaFiles(
        javaSourceFilePaths,
        outputDirectory,
        javacOptions,
        buildTarget);
  }

  static JavacStep createJavacStepForDummyRDotJavaFiles(
      Set<Path> javaSourceFilePaths,
      Path outputDirectory,
      JavacOptions javacOptions,
      BuildTarget buildTarget) {

    return new JavacStep(
        outputDirectory,
        Optional.<Path>absent(),
        javaSourceFilePaths,
        Optional.<Path>absent(),
        /* transitive classpath */ ImmutableSet.<Path>of(),
        /* declared classpath */ ImmutableSet.<Path>of(),
        JavacOptions.builder(javacOptions)
            .setAnnotationProcessingParams(AnnotationProcessingParams.EMPTY)
            .build(),
        buildTarget,
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacStep.SuggestBuildRules>absent());
  }
}
