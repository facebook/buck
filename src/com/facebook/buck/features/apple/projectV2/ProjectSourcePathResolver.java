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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Helper class to resolve {@link SourcePath}s to the cell in which we generate the Xcode project.
 */
public class ProjectSourcePathResolver {

  private final Cell projectCell;
  private final SourcePathResolverAdapter pathSourcePathResolverAdapter;
  private final TargetGraph targetGraph;
  private final Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode;

  private final ProjectFilesystem projectFilesystem;

  /**
   * @param projectCell Cell to which the project target belongs.
   * @param pathSourcePathResolverAdapter Source path resolver to use for {@link PathSourcePath}s.
   * @param targetGraph Target graph for the project target.
   * @param actionGraphBuilderForNode Action graph builder for the project target.
   */
  public ProjectSourcePathResolver(
      Cell projectCell,
      SourcePathResolverAdapter pathSourcePathResolverAdapter,
      TargetGraph targetGraph,
      Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode) {
    this.projectCell = projectCell;
    this.pathSourcePathResolverAdapter = pathSourcePathResolverAdapter;
    this.targetGraph = targetGraph;
    this.actionGraphBuilderForNode = actionGraphBuilderForNode;

    this.projectFilesystem = projectCell.getFilesystem();
  }

  /**
   * Resolve a relative path to the project cell's filesystem. {@link PathSourcePath}s utilize the
   * {@link ProjectSourcePathResolver#pathSourcePathResolverAdapter} to resolve the path. Otherwise
   * the {@code sourcePath} is expected to be a {@link BuildTargetSourcePath} for which we derive
   * the target and resolve it's output to the cell.
   *
   * @param sourcePath Source path to resolve.
   * @return A path relative to the cell.
   */
  public Path resolveSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return projectFilesystem
          .relativize(pathSourcePathResolverAdapter.getAbsolutePath(sourcePath))
          .getPath();
    }
    Preconditions.checkArgument(sourcePath instanceof BuildTargetSourcePath);
    BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) sourcePath;
    BuildTarget buildTarget = buildTargetSourcePath.getTarget();
    TargetNode<?> node = targetGraph.get(buildTarget);
    Optional<TargetNode<ExportFileDescriptionArg>> exportFileNode =
        TargetNodes.castArg(node, ExportFileDescriptionArg.class);
    if (!exportFileNode.isPresent()) {
      BuildRuleResolver resolver = actionGraphBuilderForNode.apply(node);
      Path output = resolver.getSourcePathResolver().getAbsolutePath(sourcePath);
      if (output == null) {
        throw new HumanReadableException(
            "The target '%s' does not have an output.", node.getBuildTarget());
      }

      return projectFilesystem.relativize(output).getPath();
    }

    Optional<SourcePath> src = exportFileNode.get().getConstructorArg().getSrc();
    if (!src.isPresent()) {
      Path output =
          getCellPathForTarget(buildTarget)
              .resolve(
                  buildTarget
                      .getCellRelativeBasePath()
                      .getPath()
                      .toPath(projectFilesystem.getFileSystem()))
              .resolve(buildTarget.getShortNameAndFlavorPostfix());
      return projectFilesystem.relativize(output).getPath();
    }

    return resolveSourcePath(src.get());
  }

  private Path getCellPathForTarget(BuildTarget buildTarget) {
    return projectCell.getNewCellPathResolver().getCellPath(buildTarget.getCell());
  }
}
