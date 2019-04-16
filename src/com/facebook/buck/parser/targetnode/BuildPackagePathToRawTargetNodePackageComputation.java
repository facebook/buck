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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.ImmutableRawTargetNodeWithDeps;
import com.facebook.buck.core.model.targetgraph.ImmutableRawTargetNodeWithDepsPackage;
import com.facebook.buck.core.model.targetgraph.RawTargetNodeWithDeps;
import com.facebook.buck.core.model.targetgraph.RawTargetNodeWithDepsPackage;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.RawTargetNodeToTargetNodeFactory;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.manifest.ImmutableBuildPackagePathToBuildFileManifestKey;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Transforms all targets from results of parsing a single build file to {@link
 * RawTargetNodeWithDepsPackage}
 */
public class BuildPackagePathToRawTargetNodePackageComputation
    implements GraphComputation<
        BuildPackagePathToRawTargetNodePackageKey, RawTargetNodeWithDepsPackage> {

  private final RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory;
  private final Cell cell;

  private BuildPackagePathToRawTargetNodePackageComputation(
      RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory, Cell cell) {
    this.rawTargetNodeToTargetNodeFactory = rawTargetNodeToTargetNodeFactory;
    this.cell = cell;
  }

  /** Create new instance of {@link BuildPackagePathToRawTargetNodePackageComputation} */
  public static BuildPackagePathToRawTargetNodePackageComputation of(
      RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory, Cell cell) {
    return new BuildPackagePathToRawTargetNodePackageComputation(
        rawTargetNodeToTargetNodeFactory, cell);
  }

  @Override
  public Class<BuildPackagePathToRawTargetNodePackageKey> getKeyClass() {
    return BuildPackagePathToRawTargetNodePackageKey.class;
  }

  @Override
  public RawTargetNodeWithDepsPackage transform(
      BuildPackagePathToRawTargetNodePackageKey key, ComputationEnvironment env) {

    ImmutableMap<BuildTargetToRawTargetNodeKey, RawTargetNode> rawTargetNodes =
        env.getDeps(BuildTargetToRawTargetNodeKey.class);

    Path buildFileAbsolutePath =
        cell.getRoot()
            .resolve(key.getPath())
            .resolve(cell.getBuckConfig().getView(ParserConfig.class).getBuildFileName());

    ImmutableMap.Builder<String, RawTargetNodeWithDeps> builder =
        ImmutableMap.builderWithExpectedSize(rawTargetNodes.size());

    for (Entry<BuildTargetToRawTargetNodeKey, RawTargetNode> entry : rawTargetNodes.entrySet()) {
      UnconfiguredBuildTarget unconfiguredBuildTarget = entry.getKey().getBuildTarget();
      RawTargetNode rawTargetNode = entry.getValue();

      // To discover dependencies, we coerce RawTargetNode to TargetNode, get dependencies out of
      // it, then trash target node
      // THIS SOLUTION IS TEMPORARY and not 100% correct in general, because we have to resolve
      // configuration for Target Node (we use default configuration at this point)

      // Create short living UnconfiguredBuildTargetView
      // TODO: configure data object directly
      UnconfiguredBuildTargetView unconfiguredBuildTargetView =
          ImmutableUnconfiguredBuildTargetView.of(cell.getRoot(), unconfiguredBuildTarget);

      BuildTarget buildTarget =
          unconfiguredBuildTargetView.configure(EmptyTargetConfiguration.INSTANCE);

      // All target nodes are created sequentially from raw target nodes
      // TODO: use RawTargetNodeToTargetNode transformation
      TargetNode<?> targetNode =
          rawTargetNodeToTargetNodeFactory.createTargetNode(
              cell,
              buildFileAbsolutePath,
              buildTarget,
              rawTargetNode,
              id -> SimplePerfEvent.scope(Optional.empty(), PerfEventId.of("raw_to_targetnode")));

      ImmutableSet<UnconfiguredBuildTarget> deps =
          targetNode.getParseDeps().stream()
              .map(bt -> bt.getUnconfiguredBuildTargetView().getData())
              .collect(ImmutableSet.toImmutableSet());

      // END TEMPORARY

      RawTargetNodeWithDeps rawTargetNodeWithDeps =
          ImmutableRawTargetNodeWithDeps.of(rawTargetNode, deps);
      builder.put(unconfiguredBuildTarget.getName(), rawTargetNodeWithDeps);
    }

    return ImmutableRawTargetNodeWithDepsPackage.of(builder.build());
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildPackagePathToRawTargetNodePackageKey key, ComputationEnvironment env) {

    BuildFileManifest buildFileManifest =
        env.getDep(ImmutableBuildPackagePathToBuildFileManifestKey.of(key.getPath()));
    String baseName = "//" + MorePaths.pathWithUnixSeparators(key.getPath());

    ImmutableSet.Builder<BuildTargetToRawTargetNodeKey> builder =
        ImmutableSet.builderWithExpectedSize(buildFileManifest.getTargets().size());

    for (String target : buildFileManifest.getTargets().keySet()) {
      BuildTargetToRawTargetNodeKey depkey =
          ImmutableBuildTargetToRawTargetNodeKey.of(
              ImmutableUnconfiguredBuildTarget.of(
                  cell.getCanonicalName().orElse(""),
                  baseName,
                  target,
                  UnconfiguredBuildTarget.NO_FLAVORS));
      builder.add(depkey);
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildPackagePathToRawTargetNodePackageKey key) {
    // To construct raw target node, we first need to parse a build file and obtain corresponding
    // manifest, so require it as a dependency
    return ImmutableSet.of(ImmutableBuildPackagePathToBuildFileManifestKey.of(key.getPath()));
  }
}
