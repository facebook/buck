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
package com.facebook.buck.rust;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Common implementation of RustLinkable methods.
 */
public class RustLinkables {
  private RustLinkables() {
  }

  static ImmutableSortedSet<Path> getDependencyPaths(BuildRule rule) {
    final ImmutableSortedSet.Builder<Path> builder = ImmutableSortedSet.naturalOrder();

    new AbstractBreadthFirstTraversal<BuildRule>(ImmutableSet.of(rule)) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof RustLinkable) {
          RustLinkable linkable = (RustLinkable) rule;
          builder.add(linkable.getLinkPath().getParent());
          return rule.getDeps();
        } else {
          return ImmutableSet.of();
        }
      }
    }.start();

    return builder.build();
  }
}
