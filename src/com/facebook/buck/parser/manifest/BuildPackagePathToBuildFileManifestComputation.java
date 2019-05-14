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

package com.facebook.buck.parser.manifest;

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/** Parses build file to {@link BuildFileManifest} structure */
public class BuildPackagePathToBuildFileManifestComputation
    implements GraphComputation<BuildPackagePathToBuildFileManifestKey, BuildFileManifest> {

  private final Path root;
  private final ProjectBuildFileParser parser;
  private final Path buildFileName;

  private BuildPackagePathToBuildFileManifestComputation(
      ProjectBuildFileParser parser, Path buildFileName, Path root) {
    this.parser = parser;
    this.buildFileName = buildFileName;
    this.root = root;
  }

  /**
   * Create new instance of {@link BuildPackagePathToBuildFileManifestComputation}
   *
   * @param parser Parser used to parse build file. This parser should be thread-safe.
   * @param buildFileName File name of the build file (like BUCK) expressed as a {@link Path}
   * @param root Absolute {@link Path} to the build root, usually cell root
   */
  public static BuildPackagePathToBuildFileManifestComputation of(
      ProjectBuildFileParser parser, Path buildFileName, Path root) {
    return new BuildPackagePathToBuildFileManifestComputation(parser, buildFileName, root);
  }

  @Override
  public ComputationIdentifier<BuildFileManifest> getIdentifier() {
    return BuildPackagePathToBuildFileManifestKey.IDENTIFIER;
  }

  @Override
  public BuildFileManifest transform(
      BuildPackagePathToBuildFileManifestKey key, ComputationEnvironment env) throws Exception {
    return parser.getBuildFileManifest(root.resolve(key.getPath()).resolve(buildFileName));
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildPackagePathToBuildFileManifestKey key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildPackagePathToBuildFileManifestKey key) {
    return ImmutableSet.of();
  }
}
