/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.sourcepath.resolver.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
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
  protected SourcePath resolveDefaultBuildTargetSourcePath(
      DefaultBuildTargetSourcePath targetSourcePath) {
    SourcePath resolvedPath = ruleFinder.getRule(targetSourcePath).getSourcePathToOutput();
    if (resolvedPath == null) {
      throw new HumanReadableException("No known output for: %s", targetSourcePath.getTarget());
    }
    return resolvedPath;
  }

  @Override
  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    Preconditions.checkArgument(!(sourcePath instanceof ArchiveMemberSourcePath));
    if (sourcePath instanceof BuildTargetSourcePath) {
      BuildRule rule = ruleFinder.getRule((BuildTargetSourcePath) sourcePath);
      if (rule instanceof HasOutputName) {
        HasOutputName hasOutputName = (HasOutputName) rule;
        return hasOutputName.getOutputName();
      }
      if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
        ForwardingBuildTargetSourcePath castPath = (ForwardingBuildTargetSourcePath) sourcePath;
        return getSourcePathName(target, castPath.getDelegate());
      } else if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
        Path path = ((ExplicitBuildTargetSourcePath) sourcePath).getResolvedPath();
        if (path.startsWith(rule.getProjectFilesystem().getBuckPaths().getGenDir())) {
          path = rule.getProjectFilesystem().getBuckPaths().getGenDir().relativize(path);
        }
        if (path.startsWith(rule.getBuildTarget().getBasePath())) {
          return rule.getBuildTarget().getBasePath().relativize(path).toString();
        }
      }
      return rule.getBuildTarget().getShortName();
    }
    Preconditions.checkArgument(sourcePath instanceof PathSourcePath);
    Path path = ((PathSourcePath) sourcePath).getRelativePath();
    return MorePaths.relativize(target.getBasePath(), path).toString();
  }
}
