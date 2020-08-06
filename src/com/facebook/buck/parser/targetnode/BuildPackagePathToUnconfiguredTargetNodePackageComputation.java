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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDeps;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDepsPackage;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.UnconfiguredTargetNodeToTargetNodeFactory;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.ParsingError;
import com.facebook.buck.parser.manifest.BuildPackagePathToBuildFileManifestKey;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Transforms all targets from results of parsing a single build file to {@link
 * UnconfiguredTargetNodeWithDepsPackage}
 */
public class BuildPackagePathToUnconfiguredTargetNodePackageComputation
    implements GraphComputation<
        BuildPackagePathToUnconfiguredTargetNodePackageKey, UnconfiguredTargetNodeWithDepsPackage> {

  private final UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory;
  private final Cell cell;
  private final Path superRootPath;
  private final boolean throwOnValidationError;

  private BuildPackagePathToUnconfiguredTargetNodePackageComputation(
      UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory,
      Cell cell,
      boolean throwOnValidationError,
      Path superRootPath) {
    this.unconfiguredTargetNodeToTargetNodeFactory = unconfiguredTargetNodeToTargetNodeFactory;
    this.cell = cell;
    this.superRootPath = superRootPath;
    this.throwOnValidationError = throwOnValidationError;
  }

  /**
   * Create new instance of {@link BuildPackagePathToUnconfiguredTargetNodePackageComputation}
   *
   * @param unconfiguredTargetNodeToTargetNodeFactory A factory that does translation from raw
   *     target node to configured target node. We use configured target node to infer target
   *     dependencies, but in the future we won't need that
   * @param cell Cell object that owns the package being created
   * @param throwOnValidationError If true, computation throws aborting the workflow. Otherwise a
   *     package with no targets and no deps but errors is created
   */
  public static BuildPackagePathToUnconfiguredTargetNodePackageComputation of(
      UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory,
      Cells cells,
      Cell cell,
      boolean throwOnValidationError) {
    return new BuildPackagePathToUnconfiguredTargetNodePackageComputation(
        unconfiguredTargetNodeToTargetNodeFactory,
        cell,
        throwOnValidationError,
        cells.getSuperRootPath().getPath());
  }

  @Override
  public ComputationIdentifier<UnconfiguredTargetNodeWithDepsPackage> getIdentifier() {
    return BuildPackagePathToUnconfiguredTargetNodePackageKey.IDENTIFIER;
  }

  @Override
  public UnconfiguredTargetNodeWithDepsPackage transform(
      BuildPackagePathToUnconfiguredTargetNodePackageKey key, ComputationEnvironment env) {

    ImmutableMap<BuildTargetToUnconfiguredTargetNodeKey, UnconfiguredTargetNode> rawTargetNodes =
        env.getDeps(BuildTargetToUnconfiguredTargetNodeKey.IDENTIFIER);

    Path packagePath = key.getPath();
    AbsPath buildFileAbsolutePath =
        cell.getRoot()
            .resolve(packagePath)
            .resolve(cell.getBuckConfig().getView(ParserConfig.class).getBuildFileName());

    ImmutableMap.Builder<String, UnconfiguredTargetNodeWithDeps> builder =
        ImmutableMap.builderWithExpectedSize(rawTargetNodes.size());

    ImmutableList.Builder<ParsingError> errorsBuilder = ImmutableList.builder();

    for (Entry<BuildTargetToUnconfiguredTargetNodeKey, UnconfiguredTargetNode> entry :
        rawTargetNodes.entrySet()) {
      UnconfiguredBuildTarget unconfiguredBuildTarget = entry.getKey().getBuildTarget();
      UnconfiguredTargetNode unconfiguredTargetNode = entry.getValue();

      try {
        // TODO(nga): obtain proper dependency stack
        DependencyStack dependencyStack =
            DependencyStack.top(unconfiguredTargetNode.getBuildTarget());
        ImmutableSet<UnconfiguredBuildTarget> deps =
            getTargetDeps(unconfiguredTargetNode, dependencyStack, buildFileAbsolutePath);
        UnconfiguredTargetNodeWithDeps unconfiguredTargetNodeWithDeps =
            UnconfiguredTargetNodeWithDeps.of(unconfiguredTargetNode, deps);
        builder.put(unconfiguredBuildTarget.getName(), unconfiguredTargetNodeWithDeps);
      } catch (Exception ex) {
        if (throwOnValidationError) {
          throw ex;
        }
        errorsBuilder.add(ParsingError.from(ex));
      }
      // END TEMPORARY
    }

    BuildFileManifest buildFileManifest =
        env.getDep(BuildPackagePathToBuildFileManifestKey.of(packagePath));
    FileSystem fileSystem = superRootPath.getFileSystem();
    ImmutableMap<String, UnconfiguredTargetNodeWithDeps> rawTargetNodesWithDeps = builder.build();
    ImmutableList<ParsingError> errors =
        getParsingErrors(errorsBuilder.build(), buildFileManifest.getErrors());
    /**
     * Note: Parser returns absolute paths for includes. Processing includes in way to be
     * relativized to the repo root (superroot) which might be different from cell root!
     */
    ImmutableSet<Path> includes =
        buildFileManifest.getIncludes().stream()
            .map(include -> superRootPath.relativize(fileSystem.getPath(include)))
            .collect(ImmutableSet.toImmutableSet());
    return UnconfiguredTargetNodeWithDepsPackage.of(
        packagePath, rawTargetNodesWithDeps, errors, includes);
  }

  private ImmutableList<ParsingError> getParsingErrors(
      ImmutableList<ParsingError> translateErrors, ImmutableList<ParsingError> errors) {
    if (translateErrors.isEmpty() || errors.isEmpty()) {
      return translateErrors.isEmpty() ? errors : translateErrors;
    }
    return ImmutableList.<ParsingError>builderWithExpectedSize(
            translateErrors.size() + errors.size())
        .addAll(errors)
        .addAll(translateErrors)
        .build();
  }

  private ImmutableSet<UnconfiguredBuildTarget> getTargetDeps(
      UnconfiguredTargetNode unconfiguredTargetNode,
      DependencyStack dependencyStack,
      AbsPath buildFileAbsolutePath) {
    // To discover dependencies, we coerce UnconfiguredTargetNode to TargetNode, get dependencies
    // out of it,
    // then trash target node
    // THIS SOLUTION IS TEMPORARY and not 100% correct in general, because we have to resolve
    // configuration for Target Node (we use empty configuration at this point)

    BuildTarget buildTarget =
        unconfiguredTargetNode.getBuildTarget().configure(UnconfiguredTargetConfiguration.INSTANCE);

    // All target nodes are created sequentially from raw target nodes
    // TODO: use RawTargetNodeToTargetNode transformation
    TargetNodeMaybeIncompatible targetNodeMaybeIncompatible =
        unconfiguredTargetNodeToTargetNodeFactory.createTargetNode(
            cell,
            buildFileAbsolutePath,
            buildTarget,
            dependencyStack,
            unconfiguredTargetNode,
            id ->
                SimplePerfEvent.scope(
                    Optional.empty(), SimplePerfEvent.PerfEventId.of("raw_to_targetnode")));

    return targetNodeMaybeIncompatible.assertGetTargetNode(dependencyStack).getParseDeps().stream()
        .map(BuildTarget::getUnconfiguredBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildPackagePathToUnconfiguredTargetNodePackageKey key, ComputationEnvironment env) {

    BuildFileManifest buildFileManifest =
        env.getDep(BuildPackagePathToBuildFileManifestKey.of(key.getPath()));
    ForwardRelativePath basePath = ForwardRelativePath.ofPath(key.getPath());

    ImmutableSet.Builder<BuildTargetToUnconfiguredTargetNodeKey> builder =
        ImmutableSet.builderWithExpectedSize(buildFileManifest.getTargets().size());

    for (String target : buildFileManifest.getTargets().keySet()) {
      BuildTargetToUnconfiguredTargetNodeKey depkey =
          ImmutableBuildTargetToUnconfiguredTargetNodeKey.of(
              UnconfiguredBuildTarget.of(
                  cell.getCanonicalName(), BaseName.ofPath(basePath), target, FlavorSet.NO_FLAVORS),
              key.getPath());
      builder.add(depkey);
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildPackagePathToUnconfiguredTargetNodePackageKey key) {
    // To construct raw target node, we first need to parse a build file and obtain
    // corresponding
    // manifest, so require it as a dependency
    return ImmutableSet.of(BuildPackagePathToBuildFileManifestKey.of(key.getPath()));
  }
}
