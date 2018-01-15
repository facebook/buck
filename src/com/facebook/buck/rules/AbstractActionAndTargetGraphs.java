/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Container class for {@link ActionGraphAndResolver} and {@link TargetGraphAndBuildTargets}. Also
 * contains helper methods for which {@link TargetGraph} to use for local and distributed builds
 * ({@link com.facebook.buck.versions.VersionedTargetGraph} vs un-versioned).
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractActionAndTargetGraphs {

  abstract TargetGraphAndBuildTargets getUnversionedTargetGraph();

  abstract Optional<TargetGraphAndBuildTargets> getVersionedTargetGraph();

  public abstract ActionGraphAndResolver getActionGraphAndResolver();

  /**
   * Helper method to choose versioned vs un-versioned {@link TargetGraph} to use for local builds.
   */
  public static TargetGraphAndBuildTargets getTargetGraphForLocalBuild(
      TargetGraphAndBuildTargets unversionedTargetGraph,
      Optional<TargetGraphAndBuildTargets> versionedTargetGraph) {
    // If a versioned target graph was produced then we always use this for the local build,
    // otherwise the unversioned graph is used.
    return versionedTargetGraph.isPresent() ? versionedTargetGraph.get() : unversionedTargetGraph;
  }

  /** Helper method to get the appropriate {@link TargetGraph} to use for local builds. */
  public TargetGraphAndBuildTargets getTargetGraphForLocalBuild() {
    return getTargetGraphForLocalBuild(getUnversionedTargetGraph(), getVersionedTargetGraph());
  }

  /** Helper method to get the appropriate {@link TargetGraph} to use for distributed builds. */
  public TargetGraphAndBuildTargets getTargetGraphForDistributedBuild() {
    // Distributed builds serialize and send the unversioned target graph,
    // and then deserialize and version remotely.
    return getUnversionedTargetGraph();
  }
}
