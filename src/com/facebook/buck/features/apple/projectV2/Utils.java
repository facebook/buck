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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import com.facebook.buck.swift.SwiftLibraryDescriptionArg;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** Utility functions for project generation. */
public class Utils {

  /** Generate a cell relative buck-out path for derived sources for the {@code buildTarget}. */
  static Path getDerivedSourcesDirectoryForBuildTarget(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
    String fullTargetName = buildTarget.getFullyQualifiedName();
    byte[] utf8Bytes = fullTargetName.getBytes(Charset.forName("UTF-8"));

    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(utf8Bytes);

    String targetSha1Hash = hasher.hash().toString();
    String targetFolderName = buildTarget.getShortName() + "-" + targetSha1Hash;

    Path xcodeDir = projectFilesystem.getBuckPaths().getXcodeDir();
    Path derivedSourcesDir = xcodeDir.resolve("derived-sources").resolve(targetFolderName);

    return derivedSourcesDir;
  }

  /**
   * Gets the Swift or Cxx module name if the target node has the module name defined. Otherwise
   * returns the target name.
   */
  public static String getModuleName(TargetNode<?> targetNode) {
    Optional<String> swiftName =
        TargetNodes.castArg(targetNode, SwiftLibraryDescriptionArg.class)
            .flatMap(node -> node.getConstructorArg().getModuleName());
    if (swiftName.isPresent()) {
      return swiftName.get();
    }

    return TargetNodes.castArg(targetNode, CxxLibraryDescription.CommonArg.class)
        .flatMap(node -> node.getConstructorArg().getModuleName())
        .orElse(targetNode.getBuildTarget().getShortName());
  }

  public static Optional<BuildTargetSourcePath> sourcePathTryIntoBuildTargetSourcePath(
      SourcePath sourcePath) {
    return Optional.ofNullable(
        sourcePath instanceof BuildTargetSourcePath ? (BuildTargetSourcePath) sourcePath : null);
  }

  /**
   * Adds the input source path object to the required build targets builder, if needed.
   *
   * @param buildTargetSourcePath The build target source path to add.
   * @param requiredBuildTargetsBuilder The builder to add the target too, if necessary.
   * @param targetGraph The target graph that includes the target
   * @param actionGraphBuilderForNode The action graph builder for the target node.
   */
  public static void addRequiredBuildTargetFromSourcePath(
      BuildTargetSourcePath buildTargetSourcePath,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder,
      TargetGraph targetGraph,
      Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode) {

    BuildTarget buildTarget = buildTargetSourcePath.getTarget();
    TargetNode<?> node = targetGraph.get(buildTarget);
    Optional<TargetNode<ExportFileDescriptionArg>> exportFileNode =
        TargetNodes.castArg(node, ExportFileDescriptionArg.class);
    if (!exportFileNode.isPresent()) {
      BuildRuleResolver resolver = actionGraphBuilderForNode.apply(node);
      Path output = resolver.getSourcePathResolver().getAbsolutePath(buildTargetSourcePath);
      if (output == null) {
        throw new HumanReadableException(
            "The target '%s' does not have an output.", node.getBuildTarget());
      }
      requiredBuildTargetsBuilder.add(buildTarget);
    }
  }
}
