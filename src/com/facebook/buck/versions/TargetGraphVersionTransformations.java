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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.Optional;

class TargetGraphVersionTransformations {

  private TargetGraphVersionTransformations() {}

  public static boolean isVersionPropagator(TargetNode<?, ?> node) {
    return node.getDescription() instanceof VersionPropagator;
  }

  public static boolean isVersionRoot(TargetNode<?, ?> node) {
    return (node.getDescription() instanceof VersionRoot
        && ((VersionRoot<?>) node.getDescription())
            .isVersionRoot(node.getBuildTarget().getFlavors()));
  }

  public static Optional<TargetNode<VersionedAliasDescriptionArg, ?>> getVersionedNode(
      TargetNode<?, ?> node) {
    return node.castArg(VersionedAliasDescriptionArg.class);
  }

  @SuppressWarnings("unchecked")
  public static ImmutableMap<BuildTarget, Optional<Constraint>> getVersionedDeps(
      TypeCoercerFactory typeCoercerFactory, TargetNode<?, ?> node) {
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

  public static <A, B extends Description<A>> Iterable<BuildTarget> getDeps(
      TypeCoercerFactory typeCoercerFactory, TargetNode<A, B> node) {
    return Iterables.concat(
        node.getDeclaredDeps(),
        node.getExtraDeps(),
        getVersionedDeps(typeCoercerFactory, node).keySet());
  }
}
