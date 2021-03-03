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

package com.facebook.buck.apple;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxDebugSymbolLinkStrategy;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * This strategy picks out the focused targets that are included in its build rule. The focused
 * targets are used to enable select debug info.
 */
public class AppleCxxDebugSymbolLinkStrategy implements CxxDebugSymbolLinkStrategy {

  private final ImmutableSet<AbsPath> focusedBuildOutputPaths;
  private static final Logger LOG = Logger.get(AppleCxxDebugSymbolLinkStrategy.class);

  /**
   * Adds the build rule specific focused targets to its rule key, to trigger relinking when the
   * user changes focused targets.
   */
  @AddToRuleKey private final ImmutableSet<String> focusedBuildOputputPathStrings;

  /**
   * Adds debug strategy type to rule key, ensuring the CxxLink rule to rebuild when its strategy
   * changes.
   */
  @AddToRuleKey private final String debugStrategyType = "apple-config-debug-strategy";

  public AppleCxxDebugSymbolLinkStrategy(
      ImmutableSet<String> focusedTargets,
      CellPathResolver cellPathResolver,
      ImmutableList<Arg> linkerArgs) {
    this.focusedBuildOutputPaths =
        createFocusedBuildOutputPaths(focusedTargets, cellPathResolver, linkerArgs);
    this.focusedBuildOputputPathStrings =
        focusedBuildOutputPaths.stream()
            .map(AbsPath::getPath)
            .map(Path::toString)
            .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Optional<ImmutableSet<AbsPath>> getFocusedBuildOutputPaths() {
    return Optional.of(focusedBuildOutputPaths);
  }

  private ImmutableSet<AbsPath> createFocusedBuildOutputPaths(
      ImmutableSet<String> focusedTargets,
      CellPathResolver cellPathResolver,
      ImmutableList<Arg> args) {
    // If we have no focused targets, we don't need to iterate through the
    // args to find the focused output paths.
    if (focusedTargets.isEmpty()) {
      return ImmutableSet.of();
    }

    Set<AbsPath> focusedBuildOutputPaths = new HashSet<>();

    for (Arg arg : args) {
      if (!(arg instanceof HasSourcePath)) {
        continue;
      }
      SourcePath sourcePath = ((HasSourcePath) arg).getPath();
      if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
        ExplicitBuildTargetSourcePath explicitBuildTargetSourcePath =
            (ExplicitBuildTargetSourcePath) sourcePath;
        String targetString =
            explicitBuildTargetSourcePath.getTarget().getUnflavoredBuildTarget().toString();

        if (focusedTargets.contains(targetString)) {
          Optional<AbsPath> cellAbsolutePath =
              cellPathResolver.getCellPath(explicitBuildTargetSourcePath.getTarget().getCell());

          if (cellAbsolutePath.isPresent()) {
            Path focusedTargetOutputRelativePath = explicitBuildTargetSourcePath.getResolvedPath();
            AbsPath focusedTargetOutputAbsolutePath =
                cellAbsolutePath.get().resolve(focusedTargetOutputRelativePath);
            focusedBuildOutputPaths.add(focusedTargetOutputAbsolutePath);
          } else {
            LOG.error("Failed to acquire cell root to resolve focused target absolute path.");
            // Fail fast here otherwise this can become an obscure bug to catch.
            throw new RuntimeException(
                "Failed to acquire cell absolute path! This is needed"
                    + "to resolve the absolute build paths of focused targets.");
          }
        }
      }
    }
    return ImmutableSet.copyOf(focusedBuildOutputPaths);
  }
}
