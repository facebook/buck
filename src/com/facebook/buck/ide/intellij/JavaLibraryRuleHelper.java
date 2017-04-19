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
package com.facebook.buck.ide.intellij;

import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;

import java.util.Collection;

public class JavaLibraryRuleHelper {
  /**
   * @param paths paths to check
   * @return whether any of the paths pointed to something not in the source tree.
   */
  private static boolean containsNonSourcePath(Collection<SourcePath> paths) {
    return paths.stream().anyMatch(path -> !(path instanceof PathSourcePath));
  }

  public static <T extends JavaLibraryDescription.Arg> void addCompiledShadowIfNeeded(
      IjProjectConfig projectConfig,
      TargetNode<T, ?> targetNode,
      ModuleBuildContext context) {
    if (projectConfig.isExcludeArtifactsEnabled()) {
      return;
    }

    T arg = targetNode.getConstructorArg();
    // TODO(mkosiba): investigate supporting annotation processors without resorting to this.
    boolean hasAnnotationProcessors = !arg.annotationProcessors.isEmpty();
    if (containsNonSourcePath(arg.srcs) || hasAnnotationProcessors) {
      context.addCompileShadowDep(targetNode.getBuildTarget());
    }
  }

  private JavaLibraryRuleHelper() {
  }
}
