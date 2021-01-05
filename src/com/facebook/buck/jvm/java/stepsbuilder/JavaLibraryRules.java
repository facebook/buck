/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.stepsbuilder;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.javacd.model.BaseJarCommand.AbiGenerationMode;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.DefaultSourceOnlyAbiRuleInfoFactory;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.AccumulateClassNamesStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Common utilities for working with {@link JavaLibrary} objects. */
public class JavaLibraryRules {

  /** Utility class: do not instantiate. */
  private JavaLibraryRules() {}

  /** Adds accumulate class names step to the builder */
  public static void addAccumulateClassNamesStep(
      ImmutableSet<PathMatcher> ignorePaths,
      ImmutableList.Builder<IsolatedStep> steps,
      Optional<RelPath> pathToClasses,
      RelPath pathToClassHashes) {

    RelPath dir = pathToClassHashes.getParent();
    IsolatedStep mkdirIsolatedStep = MkdirIsolatedStep.of(dir);
    IsolatedStep accumulateClassNamesStep =
        new AccumulateClassNamesStep(ignorePaths, pathToClasses, pathToClassHashes);

    steps.add(mkdirIsolatedStep);
    steps.add(accumulateClassNamesStep);
  }

  /** Reads and return java compilation data from disc. */
  public static JavaLibrary.Data initializeFromDisk(
      BuildTarget buildTarget, ProjectFilesystem filesystem) throws IOException {
    List<String> lines =
        filesystem.readLines(getPathToClassHashes(buildTarget, filesystem).getPath());
    return new JavaLibrary.Data(AccumulateClassNamesStep.parseClassHashes(lines));
  }

  /** Returns a path to class hashes */
  public static RelPath getPathToClassHashes(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s.classes.txt");
  }

  /** Creates {@link CompilerParameters} */
  public static CompilerParameters getCompilerParameters(
      ImmutableSortedSet<RelPath> compileTimeClasspathPaths,
      ImmutableSortedSet<RelPath> javaSrcs,
      ImmutableList<BaseJavaAbiInfo> fullJarInfos,
      ImmutableList<BaseJavaAbiInfo> abiJarInfos,
      String fullyQualifiedBuildTargetName,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      boolean isRequiredForSourceOnlyAbi,
      CompilerOutputPaths compilerOutputPaths) {
    return CompilerParameters.builder()
        .setClasspathEntries(compileTimeClasspathPaths)
        .setSourceFilePaths(javaSrcs)
        .setOutputPaths(compilerOutputPaths)
        .setShouldTrackClassUsage(trackClassUsage)
        .setShouldTrackJavacPhaseEvents(trackJavacPhaseEvents)
        .setAbiGenerationMode(abiGenerationMode)
        .setAbiCompatibilityMode(abiCompatibilityMode)
        .setSourceOnlyAbiRuleInfoFactory(
            DefaultSourceOnlyAbiRuleInfoFactory.of(
                fullJarInfos,
                abiJarInfos,
                fullyQualifiedBuildTargetName,
                isRequiredForSourceOnlyAbi))
        .build();
  }
}
