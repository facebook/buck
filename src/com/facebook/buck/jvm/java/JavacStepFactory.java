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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavacStepFactory {
  private final JavacOptions javacOptions;

  public JavacStepFactory(JavacOptions javacOptions) {
    this.javacOptions = javacOptions;
  }

  Step createCompileStep(
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> pathToSrcsList,
      Optional<JavacStep.SuggestBuildRules> suggestBuildRules) {
    return new JavacStep(
        outputDirectory,
        workingDirectory,
        sourceFilePaths,
        pathToSrcsList,
        declaredClasspathEntries,
        javacOptions.getJavac(),
        javacOptions,
        invokingRule,
        suggestBuildRules,
        resolver,
        filesystem);
  }
}
