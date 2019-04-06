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

import com.facebook.buck.core.graph.transformation.GraphTransformer;
import com.facebook.buck.core.graph.transformation.TransformationEnvironment;
import com.facebook.buck.core.graph.transformation.compute.ComputeKey;
import com.facebook.buck.core.graph.transformation.compute.ComputeResult;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/** Parses build file to {@link BuildFileManifest} structure */
public class BuildPackagePathToBuildFileManifestTransformer
    implements GraphTransformer<BuildPackagePathToBuildFileManifestKey, BuildFileManifest> {

  private final ProjectBuildFileParser parser;
  private final Path buildFileName;

  private BuildPackagePathToBuildFileManifestTransformer(
      ProjectBuildFileParser parser, Path buildFileName) {
    this.parser = parser;
    this.buildFileName = buildFileName;
  }

  /**
   * Create new instance of {@link BuildPackagePathToBuildFileManifestTransformer}
   *
   * @param parser Parser used to parse build file. This parser should be thread-safe.
   */
  public static BuildPackagePathToBuildFileManifestTransformer of(
      ProjectBuildFileParser parser, Path buildFileName) {
    return new BuildPackagePathToBuildFileManifestTransformer(parser, buildFileName);
  }

  @Override
  public Class<BuildPackagePathToBuildFileManifestKey> getKeyClass() {
    return BuildPackagePathToBuildFileManifestKey.class;
  }

  @Override
  public BuildFileManifest transform(
      BuildPackagePathToBuildFileManifestKey key, TransformationEnvironment env) throws Exception {
    return parser.getBuildFileManifest(key.getPath().resolve(buildFileName));
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildPackagePathToBuildFileManifestKey key, TransformationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildPackagePathToBuildFileManifestKey key) {
    return ImmutableSet.of();
  }
}
