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

package com.facebook.buck.parser.manifest;

import com.facebook.buck.core.filesystems.FileName;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.ParsingError;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/** Parses build file to {@link BuildFileManifest} structure */
public class BuildPackagePathToBuildFileManifestComputation
    implements GraphComputation<BuildPackagePathToBuildFileManifestKey, BuildFileManifest> {

  private final ProjectBuildFileParser parser;
  private final FileName buildFileName;
  private final boolean throwOnParseError;

  private BuildPackagePathToBuildFileManifestComputation(
      ProjectBuildFileParser parser, FileName buildFileName, boolean throwOnParseError) {
    this.parser = parser;
    this.buildFileName = buildFileName;
    this.throwOnParseError = throwOnParseError;
  }

  /**
   * Create new instance of {@link BuildPackagePathToBuildFileManifestComputation}
   *
   * @param parser Parser used to parse build file. This parser should be thread-safe.
   * @param buildFileName File name of the build file (like BUCK) expressed as a {@link Path}
   * @param throwOnParseError If true, error in parsing of a build file results in exception thrown.
   *     Otherwise an empty {@link BuildFileManifest} is created and filled with error information.
   */
  public static BuildPackagePathToBuildFileManifestComputation of(
      ProjectBuildFileParser parser, FileName buildFileName, boolean throwOnParseError) {
    return new BuildPackagePathToBuildFileManifestComputation(
        parser, buildFileName, throwOnParseError);
  }

  @Override
  public ComputationIdentifier<BuildFileManifest> getIdentifier() {
    return BuildPackagePathToBuildFileManifestKey.IDENTIFIER;
  }

  @Override
  public BuildFileManifest transform(
      BuildPackagePathToBuildFileManifestKey key, ComputationEnvironment env) throws Exception {
    try {
      return parser.getManifest(ForwardRelPath.ofPath(key.getPath()).resolve(buildFileName));
    } catch (BuildFileParseException ex) {
      if (throwOnParseError) {
        throw ex;
      }

      return BuildFileManifest.of(
          TwoArraysImmutableHashMap.of(),
          ImmutableSet.of(),
          ImmutableMap.of(),
          ImmutableList.of(),
          ImmutableList.of(ParsingError.from(ex)));
    }
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
