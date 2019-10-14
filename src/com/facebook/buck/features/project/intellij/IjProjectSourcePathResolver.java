/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A SourcePathResolver implementation that uses only information found in the target graph when
 * converting a BuildTargetSourcePath to an outputPath. This allows the IjProject code to rely only
 * on the TargetGraph when constructing a project.
 */
public class IjProjectSourcePathResolver extends AbstractSourcePathResolver {

  private final TargetGraph targetGraph;

  public IjProjectSourcePathResolver(TargetGraph targetGraph) {
    this.targetGraph = targetGraph;
  }

  /**
   * This function mimics the behavior of `BuildRule#getSourcePathToOutput()` but does so without
   * access to the BuildRule implementations or the ActionGraph. This is important since it allows
   * us to resolve SourcePaths from the TargetGraph alone.
   *
   * @return the output path for the given targetNode or Optional.empty() if we don't know how to
   *     resolve the given TargetNode type to an output path.
   */
  private Optional<Path> getOutputPathForTargetNode(TargetNode<?> targetNode) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  /**
   * Resolve the default output path for the given targetSourcePath, returning a sourcepath pointing
   * to the output.
   */
  @Override
  protected SourcePath resolveDefaultBuildTargetSourcePath(
      DefaultBuildTargetSourcePath targetSourcePath) {
    BuildTarget target = targetSourcePath.getTarget();
    TargetNode<?> targetNode = targetGraph.get(target);
    Optional<Path> outputPath = getOutputPathForTargetNode(targetNode);
    return PathSourcePath.of(
        targetNode.getFilesystem(),
        outputPath.orElseThrow(
            () -> new HumanReadableException("No known output for: %s", target)));
  }

  /**
   * Source path names are only used when constructing the ActionGraph, so we don't need to support
   * them here.
   */
  @Override
  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    throw new UnsupportedOperationException();
  }

  /** @return the filesystem instance that corresponds to the given BuildTargetSourcePath */
  @Override
  protected ProjectFilesystem getBuildTargetSourcePathFilesystem(BuildTargetSourcePath sourcePath) {
    return targetGraph.get(sourcePath.getTarget()).getFilesystem();
  }

  /** @return The BuildTarget portion of the sourcePath if present */
  public Optional<BuildTarget> getBuildTarget(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return Optional.of(((BuildTargetSourcePath) sourcePath).getTarget());
    } else {
      return Optional.empty();
    }
  }
}
