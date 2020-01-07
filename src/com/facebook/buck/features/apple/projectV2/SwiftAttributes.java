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
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Attributes that are specific to target's containing Swift code. */
@BuckStyleValueWithBuilder
abstract class SwiftAttributes {

  abstract TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode();

  /** The module name of the Swift target. */
  @Value.Derived
  String moduleName() {
    return Utils.getModuleName(targetNode());
  }

  /** Swift compiler version that the target supports. */
  abstract Optional<String> swiftVersion();

  /**
   * The generated bridging header name for Objective C code. Typically @{code moduleName}-Swift.h.
   */
  @Value.Derived
  String objCGeneratedHeaderName() {
    return SwiftAttributeParser.getSwiftObjCGeneratedHeaderName(
        targetNode(), Optional.of(moduleName()));
  }

  /** Map of target relative generated bridging header path to absolute path in the filesystem. */
  @Value.Default
  ImmutableMap<Path, Path> publicHeaderMapEntries() {
    return ImmutableMap.of();
  };
}
