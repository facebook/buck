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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig.ToolType;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public class ClangCompiler extends DefaultCompiler {
  /**
   * Whether we should use -MD (dependency list) or -H (dependency tree) for dependency tracking.
   */
  /** The tree may be used for detailed untracked header error message but may hurt performance. */
  @AddToRuleKey private final boolean useDependencyTree;

  @AddToRuleKey private final DependencyTrackingMode dependencyTrackingMode;
  @AddToRuleKey private final ToolType toolType;

  public ClangCompiler(Tool tool, ToolType toolType, boolean useDependencyTree) {
    this(tool, toolType, useDependencyTree, true);
  }

  public ClangCompiler(
      Tool tool, ToolType toolType, boolean useDependencyTree, boolean useUnixPathSeparator) {
    super(tool, useUnixPathSeparator);
    this.useDependencyTree = useDependencyTree;
    this.toolType = toolType;
    if (useDependencyTree) {
      dependencyTrackingMode = DependencyTrackingMode.SHOW_HEADERS;
    } else {
      dependencyTrackingMode = DependencyTrackingMode.MAKEFILE;
    }
  }

  @Override
  public DependencyTrackingMode getDependencyTrackingMode() {
    return dependencyTrackingMode;
  }

  @Override
  public ImmutableList<String> outputDependenciesArgs(String outputPath) {
    if (useDependencyTree) {
      return ImmutableList.of("-H");
    } else {
      return ImmutableList.of("-MD", "-MF", MorePaths.pathWithUnixSeparators(outputPath));
    }
  }

  @Override
  public ImmutableList<String> getFlagsForReproducibleBuild(
      String altCompilationDir, Path currentCellPath) {
    return ImmutableList.of("-Xclang", "-fdebug-compilation-dir", "-Xclang", altCompilationDir);
  }

  @Override
  public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
    // We invoke asm compiler as clang but asm compiler doesn't support color diagnostics flag.
    if (toolType == ToolType.ASM || toolType == ToolType.AS) {
      return Optional.empty();
    } else {
      return Optional.of(ImmutableList.of("-fcolor-diagnostics"));
    }
  }

  @Override
  public boolean isArgFileSupported() {
    return true;
  }
}
