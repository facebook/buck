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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.step.isolatedsteps.common.RmIsolatedStep;
import com.google.common.collect.ImmutableSet;

@BuckStyleValue
public abstract class RmStep extends DelegateStep<RmIsolatedStep> {

  abstract BuildCellRelativePath getPath();

  abstract boolean isRecursive();

  public abstract ImmutableSet<RelPath> getExcludedPaths();

  @Override
  protected String getShortNameSuffix() {
    return "rm";
  }

  @Override
  protected RmIsolatedStep createDelegate(StepExecutionContext context) {

    return RmIsolatedStep.of(
        toCellRootRelativePath(context, getPath()),
        isRecursive(),
        getCellRootRelativeExcludedPaths(context));
  }

  private ImmutableSet<RelPath> getCellRootRelativeExcludedPaths(StepExecutionContext context) {
    ImmutableSet<RelPath> excludedPaths = getExcludedPaths();
    if (excludedPaths.isEmpty()) {
      // Avoid overhead of calling .stream() etc as almost all steps will not have excluded files
      return ImmutableSet.of();
    }

    return excludedPaths.stream()
        .map(relPath -> toCellRootRelativePath(context, relPath))
        .collect(ImmutableSet.toImmutableSet());
  }

  public static RmStep recursiveOf(
      BuildCellRelativePath path, ImmutableSet<RelPath> excludedPaths) {
    return ImmutableRmStep.ofImpl(path, true, excludedPaths);
  }

  public static RmStep of(BuildCellRelativePath path, boolean recursive) {
    return ImmutableRmStep.ofImpl(path, recursive, ImmutableSet.of());
  }

  public static RmStep of(BuildCellRelativePath path) {
    return of(path, false);
  }
}
