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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.swift.SwiftLibraryDescriptionArg;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

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
}
