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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDepsPackage;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * Transformation key containing a path to a build package to get parsed {@link
 * UnconfiguredTargetNodeWithDepsPackage} from it
 */
@BuckStylePrehashedValue
public abstract class BuildPackagePathToUnconfiguredTargetNodePackageKey
    implements ComputeKey<UnconfiguredTargetNodeWithDepsPackage> {

  public static final ComputationIdentifier<UnconfiguredTargetNodeWithDepsPackage> IDENTIFIER =
      ClassBasedComputationIdentifier.of(
          BuildPackagePathToUnconfiguredTargetNodePackageKey.class,
          UnconfiguredTargetNodeWithDepsPackage.class);

  /**
   * A path to a package root directory, i.e. a directory containing build file, relative to some
   * root (usually cell folder root)
   */
  public abstract Path getPath();

  public static BuildPackagePathToUnconfiguredTargetNodePackageKey of(Path path) {
    return ImmutableBuildPackagePathToUnconfiguredTargetNodePackageKey.of(path);
  }

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(!getPath().isAbsolute());
  }

  @Override
  public ComputationIdentifier<UnconfiguredTargetNodeWithDepsPackage> getIdentifier() {
    return IDENTIFIER;
  }
}
