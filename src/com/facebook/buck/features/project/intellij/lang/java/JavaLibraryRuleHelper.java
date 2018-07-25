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
package com.facebook.buck.features.project.intellij.lang.java;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.project.intellij.JavaLanguageLevelHelper;
import com.facebook.buck.features.project.intellij.ModuleBuildContext;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import java.util.Collection;
import java.util.Optional;

public class JavaLibraryRuleHelper {
  /**
   * @param paths paths to check
   * @return whether any of the paths pointed to something not in the source tree.
   */
  private static boolean containsNonSourcePath(Collection<SourcePath> paths) {
    return paths.stream().anyMatch(path -> !(path instanceof PathSourcePath));
  }

  public static <T extends JavaLibraryDescription.CoreArg> void addCompiledShadowIfNeeded(
      IjProjectConfig projectConfig, TargetNode<T> targetNode, ModuleBuildContext context) {
    if (projectConfig.isExcludeArtifactsEnabled()) {
      return;
    }

    T arg = targetNode.getConstructorArg();
    // TODO(mkosiba): investigate supporting annotation processors without resorting to this.
    boolean hasAnnotationProcessors = !arg.getAnnotationProcessors().isEmpty();
    if (containsNonSourcePath(arg.getSrcs()) || hasAnnotationProcessors) {
      context.addCompileShadowDep(targetNode.getBuildTarget());
    }
  }

  public static <T extends JavaLibraryDescription.CoreArg> Optional<String> getLanguageLevel(
      IjProjectConfig projectConfig, TargetNode<T> targetNode) {

    JavaLibraryDescription.CoreArg arg = targetNode.getConstructorArg();

    if (arg.getSource().isPresent()) {
      JavacOptions defaultJavacOptions = projectConfig.getJavaBuckConfig().getDefaultJavacOptions();
      String defaultSourceLevel = defaultJavacOptions.getSourceLevel();
      String defaultTargetLevel = defaultJavacOptions.getTargetLevel();
      boolean languageLevelsAreDifferent =
          !defaultSourceLevel.equals(arg.getSource().orElse(defaultSourceLevel))
              || !defaultTargetLevel.equals(arg.getTarget().orElse(defaultTargetLevel));
      if (languageLevelsAreDifferent) {
        return Optional.of(JavaLanguageLevelHelper.normalizeSourceLevel(arg.getSource().get()));
      }
    }

    return Optional.empty();
  }

  public static <T extends JavaLibraryDescription.CoreArg> void addNonSourceBuildTargets(
      TargetNode<T> targetNode, ModuleBuildContext context) {
    T arg = targetNode.getConstructorArg();
    if (arg.getSrcs().stream().anyMatch(src -> src instanceof BuildTargetSourcePath)) {
      context.addNonSourceBuildTarget(targetNode.getBuildTarget());
    }
  }

  private JavaLibraryRuleHelper() {}
}
