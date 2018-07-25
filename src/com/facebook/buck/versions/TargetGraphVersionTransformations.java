/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.Optional;

class TargetGraphVersionTransformations {

  private TargetGraphVersionTransformations() {}

  public static boolean isVersionPropagator(TargetNode<?> node) {
    return node.getDescription() instanceof VersionPropagator;
  }

  public static boolean isVersionRoot(TargetNode<?> node) {
    return (node.getDescription() instanceof VersionRoot
        && ((VersionRoot<?>) node.getDescription())
            .isVersionRoot(node.getBuildTarget().getFlavors()));
  }

  public static Optional<TargetNode<VersionedAliasDescriptionArg>> getVersionedNode(
      TargetNode<?> node) {
    return TargetNodes.castArg(node, VersionedAliasDescriptionArg.class);
  }

  @SuppressWarnings("unchecked")
  public static ImmutableMap<BuildTarget, Optional<Constraint>> getVersionedDeps(
      TypeCoercerFactory typeCoercerFactory, TargetNode<?> node) {
    ParamInfo versionedDepsParam =
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(typeCoercerFactory, node.getConstructorArg().getClass())
            .get("versioned_deps");
    if (versionedDepsParam == null) {
      return ImmutableMap.of();
    }
    return (ImmutableMap<BuildTarget, Optional<Constraint>>)
        versionedDepsParam.get(node.getConstructorArg());
  }

  public static <A, B extends DescriptionWithTargetGraph<A>> Iterable<BuildTarget> getDeps(
      TypeCoercerFactory typeCoercerFactory, TargetNode<A> node) {
    return Iterables.concat(
        node.getDeclaredDeps(),
        node.getExtraDeps(),
        // technically target graph only deps may not have to be versioned, but since they can
        // include things like platform-specific dependencies, which may actually end up being used
        // at build time, we have to include them here as well.
        // TODO(buck-team): consider adding a separate field to target node to avoid potential
        // confusion.
        node.getTargetGraphOnlyDeps(),
        getVersionedDeps(typeCoercerFactory, node).keySet());
  }
}
