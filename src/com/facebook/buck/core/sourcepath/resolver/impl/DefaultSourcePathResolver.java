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

package com.facebook.buck.core.sourcepath.resolver.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class DefaultSourcePathResolver extends AbstractSourcePathResolver {

  private final SourcePathRuleFinder ruleFinder;

  DefaultSourcePathResolver(SourcePathRuleFinder ruleFinder) {
    this.ruleFinder = ruleFinder;
  }

  public static DefaultSourcePathResolver from(SourcePathRuleFinder ruleFinder) {
    return new DefaultSourcePathResolver(ruleFinder);
  }

  @Override
  protected ProjectFilesystem getBuildTargetSourcePathFilesystem(BuildTargetSourcePath sourcePath) {
    return ruleFinder.getRule(sourcePath).getProjectFilesystem();
  }

  @Override
  protected ImmutableSortedSet<SourcePath> resolveDefaultBuildTargetSourcePath(
      DefaultBuildTargetSourcePath targetSourcePath) {
    BuildTargetWithOutputs buildTargetWithOutputs = targetSourcePath.getTargetWithOutputs();
    BuildRule rule = ruleFinder.getRule(targetSourcePath);
    if (rule instanceof HasMultipleOutputs) {
      ImmutableSortedSet<SourcePath> resolvedPaths =
          ((HasMultipleOutputs) rule)
              .getSourcePathToOutput(buildTargetWithOutputs.getOutputLabel());
      if (resolvedPaths == null || resolvedPaths.isEmpty()) {
        throw new HumanReadableException(
            "No known output for: %s", targetSourcePath.getTargetWithOutputs());
      }
      return resolvedPaths;
    }
    Preconditions.checkState(
        buildTargetWithOutputs.getOutputLabel().isDefault(),
        "Multiple outputs not supported for %s target %s",
        rule.getType(),
        targetSourcePath.getTargetWithOutputs());
    SourcePath resolvedPath = rule.getSourcePathToOutput();
    if (resolvedPath == null) {
      throw new HumanReadableException(
          "No known output for: %s", targetSourcePath.getTargetWithOutputs());
    }
    return ImmutableSortedSet.of(resolvedPath);
  }

  @Override
  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    Preconditions.checkArgument(!(sourcePath instanceof ArchiveMemberSourcePath));
    if (sourcePath instanceof BuildTargetSourcePath) {
      BuildRule rule = ruleFinder.getRule((BuildTargetSourcePath) sourcePath);
      if (rule instanceof HasOutputName) {
        HasOutputName hasOutputName = (HasOutputName) rule;
        return hasOutputName.getOutputName(
            ((BuildTargetSourcePath) sourcePath).getTargetWithOutputs().getOutputLabel());
      }
      if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
        ForwardingBuildTargetSourcePath castPath = (ForwardingBuildTargetSourcePath) sourcePath;
        return getSourcePathName(target, castPath.getDelegate());
      } else if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
        Path path = ((ExplicitBuildTargetSourcePath) sourcePath).getResolvedPath();
        Path basePath =
            rule.getProjectFilesystem()
                .getBuckPaths()
                .getGenDir()
                .resolve(
                    BuildTargetPaths.getBasePathForBaseName(
                            rule.getProjectFilesystem(), rule.getBuildTarget())
                        .toPath(path.getFileSystem()));
        if (path.startsWith(basePath)) {
          return basePath.relativize(path).toString();
        }
        // TODO(nga): path must start with base dir
      }
      return rule.getBuildTarget().getShortName();
    }
    Preconditions.checkArgument(sourcePath instanceof PathSourcePath);
    Path path = ((PathSourcePath) sourcePath).getRelativePath();
    return MorePaths.relativize(
            target.getCellRelativeBasePath().getPath().toPath(path.getFileSystem()), path)
        .toString();
  }
}
