/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphAndTargets;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.stream.Collectors;

public class VersionedTargetGraphAndTargets {

  public static TargetGraphAndTargets toVersionedTargetGraphAndTargets(
      TargetGraphAndTargets targetGraphAndTargets,
      InstrumentedVersionedTargetGraphCache versionedTargetGraphCache,
      BuckEventBus buckEventBus,
      BuckConfig buckConfig,
      TypeCoercerFactory typeCoercerFactory,
      ImmutableSet<BuildTarget> explicitTestTargets)
      throws VersionException, InterruptedException {
    TargetGraphAndBuildTargets targetGraphAndBuildTargets =
        TargetGraphAndBuildTargets.of(
            targetGraphAndTargets.getTargetGraph(),
            Sets.union(
                targetGraphAndTargets
                    .getProjectRoots()
                    .stream()
                    .map(root -> root.getBuildTarget())
                    .collect(Collectors.toSet()),
                explicitTestTargets));
    TargetGraphAndBuildTargets versionedTargetGraphAndBuildTargets =
        versionedTargetGraphCache.toVersionedTargetGraph(
            buckEventBus, buckConfig, typeCoercerFactory, targetGraphAndBuildTargets);
    return new TargetGraphAndTargets(
        versionedTargetGraphAndBuildTargets.getTargetGraph(),
        targetGraphAndTargets.getProjectRoots());
  }
}
