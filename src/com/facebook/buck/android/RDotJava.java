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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.ClasspathChecker;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacStep;
import com.facebook.buck.jvm.java.NoOpClassUsageFileWriter;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;

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

  static JavacStep createJavacStepForDummyRDotJavaFiles(
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Path outputDirectory,
      JavacOptions javacOptions,
      BuildTarget buildTarget,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem) {

    return new JavacStep(
        outputDirectory,
        NoOpClassUsageFileWriter.instance(),
        Optional.empty(),
        javaSourceFilePaths,
        pathToSrcsList,
        /* declared classpath */ ImmutableSortedSet.of(),
        javacOptions.getJavac(),
        JavacOptions.builder(javacOptions)
            .setAnnotationProcessingParams(AnnotationProcessingParams.EMPTY)
            .build(),
        buildTarget,
        Optional.empty(),
        resolver,
        filesystem,
        new ClasspathChecker(),
        /* directToJarOutputSettings */ Optional.empty());
  }
}
