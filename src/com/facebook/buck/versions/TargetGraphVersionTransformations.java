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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.lang.reflect.Field;
import java.util.Optional;

class TargetGraphVersionTransformations {

  public static final String VERSIONED_DEPS_FIELD_NAME = "versionedDeps";

  private TargetGraphVersionTransformations() {}

  public static boolean isVersionPropagator(TargetNode<?, ?> node) {
    return node.getDescription() instanceof VersionPropagator;
  }

  public static boolean isVersionRoot(TargetNode<?, ?> node) {
    return (
        node.getDescription() instanceof VersionRoot &&
        ((VersionRoot<?>) node.getDescription()).isVersionRoot(node.getBuildTarget().getFlavors()));
  }

  public static Optional<TargetNode<VersionedAliasDescription.Arg, ?>> getVersionedNode(
      TargetNode<?, ?> node) {
    return node.castArg(VersionedAliasDescription.Arg.class);
  }

  private static Optional<Field> getVersionedDepsField(TargetNode<?, ?> node) {
    try {
      return Optional.of(node.getConstructorArg().getClass().getField(VERSIONED_DEPS_FIELD_NAME));
    } catch (NoSuchFieldException e) {
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  public static ImmutableMap<BuildTarget, Optional<Constraint>> getVersionedDeps(
      TargetNode<?, ?> node) {
    Optional<Field> versionedDepsField = getVersionedDepsField(node);
    if (versionedDepsField.isPresent()) {
      try {
        ImmutableMap<BuildTarget, Optional<Constraint>> versionedDeps =
            (ImmutableMap<BuildTarget, Optional<Constraint>>)
                versionedDepsField.get().get(node.getConstructorArg());
        return versionedDeps;
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    return ImmutableMap.of();
  }

  public static <A, B extends Description<A>> Iterable<BuildTarget> getDeps(
      TargetNode<A, B> node) {
    return Iterables.concat(
        node.getDeclaredDeps(),
        node.getExtraDeps(),
        getVersionedDeps(node).keySet());
  }

}
