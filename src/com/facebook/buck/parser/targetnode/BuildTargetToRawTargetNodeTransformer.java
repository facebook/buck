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
import com.facebook.buck.core.graph.transformation.ComputeKey;
import com.facebook.buck.core.graph.transformation.ComputeResult;
import com.facebook.buck.core.graph.transformation.GraphTransformer;
import com.facebook.buck.core.graph.transformation.TransformationEnvironment;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.RawTargetNodeFactory;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.parser.manifest.BuildFilePathToBuildFileManifestKey;
import com.facebook.buck.parser.manifest.ImmutableBuildFilePathToBuildFileManifestKey;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map;
import javax.annotation.Nullable;

/** Transforms one target from specific {@link BuildFileManifest} to {@link RawTargetNode} */
public class BuildTargetToRawTargetNodeTransformer
    implements GraphTransformer<BuildTargetToRawTargetNodeKey, RawTargetNode> {

  private final RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory;
  private final Cell cell;

  private BuildTargetToRawTargetNodeTransformer(
      RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory, Cell cell) {
    this.rawTargetNodeFactory = rawTargetNodeFactory;
    this.cell = cell;
  }

  /**
   * Create new instance of {@link BuildTargetToRawTargetNodeTransformer}
   *
   * @param rawTargetNodeFactory An actual factory that will create {@link RawTargetNode} from raw
   *     attributes containing in {@link BuildFileManifest}
   * @param cell A {@link Cell} object that contains targets used in this transformation, it is
   *     mostly used to resolve paths to absolute paths
   * @return
   */
  public static BuildTargetToRawTargetNodeTransformer of(
      RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory, Cell cell) {
    return new BuildTargetToRawTargetNodeTransformer(rawTargetNodeFactory, cell);
  }

  @Override
  public Class<BuildTargetToRawTargetNodeKey> getKeyClass() {
    return BuildTargetToRawTargetNodeKey.class;
  }

  @Override
  public RawTargetNode transform(BuildTargetToRawTargetNodeKey key, TransformationEnvironment env) {
    UnconfiguredBuildTarget buildTarget = key.getBuildTarget();

    BuildFileManifest manifest = env.getDep(getManifestKey(key));
    @Nullable
    Map<String, Object> rawAttributes = manifest.getTargets().get(buildTarget.getShortName());
    if (rawAttributes == null) {
      throw new NoSuchBuildTargetException(buildTarget);
    }

    return rawTargetNodeFactory.create(
        cell,
        // TODO(sergeyb): this is expensive and involves filesystem access. Pass relative path along
        // with manifest, then resolve it here, or may be include it with build target itself
        cell.getBuckConfigView(ParserConfig.class).getAbsolutePathToBuildFile(cell, buildTarget),
        buildTarget,
        rawAttributes);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildTargetToRawTargetNodeKey key, TransformationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildTargetToRawTargetNodeKey key) {
    // To construct raw target node, we first need to parse a build file and obtain corresponding
    // manifest, so require it as a dependency
    return ImmutableSet.of(getManifestKey(key));
  }

  private BuildFilePathToBuildFileManifestKey getManifestKey(BuildTargetToRawTargetNodeKey key) {
    UnconfiguredBuildTarget buildTarget = key.getBuildTarget();

    // TODO(sergeyb): this is expensive and involves filesystem access, and happens twice for same
    // raw target and multiple times per same manifest. The path should come from upstream as input.
    Path absolutePath =
        cell.getBuckConfigView(ParserConfig.class).getAbsolutePathToBuildFile(cell, buildTarget);
    Path relativePath = cell.getRoot().relativize(absolutePath);

    return ImmutableBuildFilePathToBuildFileManifestKey.of(relativePath);
  }
}
