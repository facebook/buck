/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Matches all {@link TargetNode} objects in a repository that match the given {@link Predicate}.
 */
public class TargetNodePredicateSpec implements TargetNodeSpec {

  private final Predicate<TargetNode<?>> predicate;
  private final BuildFileSpec buildFileSpec;

  public TargetNodePredicateSpec(
      Predicate<TargetNode<?>> predicate,
      ImmutableSet<Path> ignoreDirs) {
    this.predicate = predicate;
    this.buildFileSpec = BuildFileSpec.fromRecursivePath(Paths.get(""), ignoreDirs);
  }

  @Override
  public ImmutableSet<BuildTarget> filter(Iterable<TargetNode<?>> nodes) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();

    for (TargetNode<?> node : nodes) {
      if (predicate.apply(node)) {
        targets.add(node.getBuildTarget());
      }
    }

    return targets.build();
  }

  @Override
  public BuildFileSpec getBuildFileSpec() {
    return buildFileSpec;
  }

}
