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
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.parser.RawTargetNodeFactory;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.parser.manifest.BuildPackagePathToBuildFileManifestKey;
import com.facebook.buck.parser.manifest.ImmutableBuildPackagePathToBuildFileManifestKey;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import javax.annotation.Nullable;

/** Transforms one target from specific {@link BuildFileManifest} to {@link RawTargetNode} */
public class BuildTargetToRawTargetNodeComputation
    implements GraphComputation<BuildTargetToRawTargetNodeKey, RawTargetNode> {

  private final RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory;
  private final Cell cell;

  private BuildTargetToRawTargetNodeComputation(
      RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory, Cell cell) {
    this.rawTargetNodeFactory = rawTargetNodeFactory;
    this.cell = cell;
  }

  /**
   * Create new instance of {@link BuildTargetToRawTargetNodeComputation}
   *
   * @param rawTargetNodeFactory An actual factory that will create {@link RawTargetNode} from raw
   *     attributes containing in {@link BuildFileManifest}
   * @param cell A {@link Cell} object that contains targets used in this transformation, it is
   *     mostly used to resolve paths to absolute paths
   * @return
   */
  public static BuildTargetToRawTargetNodeComputation of(
      RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory, Cell cell) {
    return new BuildTargetToRawTargetNodeComputation(rawTargetNodeFactory, cell);
  }

  @Override
  public Class<BuildTargetToRawTargetNodeKey> getKeyClass() {
    return BuildTargetToRawTargetNodeKey.class;
  }

  @Override
  public RawTargetNode transform(BuildTargetToRawTargetNodeKey key, ComputationEnvironment env) {
    UnconfiguredBuildTarget buildTarget = key.getBuildTarget();

    BuildFileManifest manifest = env.getDep(getManifestKey(key));
    @Nullable Map<String, Object> rawAttributes = manifest.getTargets().get(buildTarget.getName());
    if (rawAttributes == null) {
      throw new NoSuchBuildTargetException(buildTarget);
    }

    /** TODO: do it directly not using {@link UnconfiguredBuildTargetView} */
    UnconfiguredBuildTargetView unconfiguredBuildTargetView =
        ImmutableUnconfiguredBuildTargetView.of(cell.getRoot(), buildTarget);

    return rawTargetNodeFactory.create(
        cell,
        unconfiguredBuildTargetView.getBasePath(),
        unconfiguredBuildTargetView,
        rawAttributes);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildTargetToRawTargetNodeKey key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildTargetToRawTargetNodeKey key) {
    // To construct raw target node, we first need to parse a build file and obtain corresponding
    // manifest, so require it as a dependency
    return ImmutableSet.of(getManifestKey(key));
  }

  private BuildPackagePathToBuildFileManifestKey getManifestKey(BuildTargetToRawTargetNodeKey key) {
    UnconfiguredBuildTarget buildTarget = key.getBuildTarget();

    /** TODO: do it directly not using {@link UnconfiguredBuildTargetView} */
    UnconfiguredBuildTargetView unconfiguredBuildTargetView =
        ImmutableUnconfiguredBuildTargetView.of(cell.getRoot(), buildTarget);

    return ImmutableBuildPackagePathToBuildFileManifestKey.of(
        unconfiguredBuildTargetView.getBasePath());
  }
}
