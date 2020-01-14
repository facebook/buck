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

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Attributes specific to generating and providing Header Search Paths to Xcode for a target. */
@BuckStyleValueWithBuilder
abstract class HeaderSearchPathAttributes {

  abstract TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode();

  /** Target relative Path to SourcePath for all public headers. */
  @Value.Default
  ImmutableSortedMap<Path, SourcePath> publicCxxHeaders() {
    return ImmutableSortedMap.of();
  }

  /** Target relative Path to SourcePath for all private headers. */
  @Value.Default
  ImmutableSortedMap<Path, SourcePath> privateCxxHeaders() {
    return ImmutableSortedMap.of();
  }

  /** Sourcepaths that must be built/generated. */
  @Value.Default
  ImmutableList<SourcePath> sourcePathsToBuild() {
    return ImmutableList.of();
  }

  /** Recursively derived public system include paths for the target. */
  @Value.Default
  ImmutableSet<Path> recursivePublicSystemIncludeDirectories() {
    return ImmutableSet.of();
  }

  /** Recursively derived public include paths for the target. */
  @Value.Default
  ImmutableSet<Path> recursivePublicIncludeDirectories() {
    return ImmutableSet.of();
  }

  /** Include paths for the target. */
  @Value.Default
  ImmutableSet<Path> includeDirectories() {
    return ImmutableSet.of();
  }

  /** Recursively derived absolute header search paths for the target for private headers. */
  @Value.Default
  ImmutableSet<Path> recursiveHeaderSearchPaths() {
    return ImmutableSet.of();
  }

  /** Swift include paths for the target. */
  @Value.Default
  ImmutableSet<Path> swiftIncludePaths() {
    return ImmutableSet.of();
  }
}
