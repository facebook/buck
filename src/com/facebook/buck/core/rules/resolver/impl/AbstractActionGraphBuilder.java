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

package com.facebook.buck.core.rules.resolver.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.AbstractBuildRuleResolver;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.function.Function;

/** An abstract implementation of BuildRuleResolver that simplifies concrete implementations. */
public abstract class AbstractActionGraphBuilder extends AbstractBuildRuleResolver
    implements ActionGraphBuilder {
  @Override
  public ImmutableSortedSet<BuildRule> requireAllRules(Iterable<BuildTarget> buildTargets) {
    return RichStream.from(buildTargets)
        .map(this::requireRule)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public ImmutableSortedMap<BuildTarget, BuildRule> computeAllIfAbsent(
      ImmutableMap<BuildTarget, Function<BuildTarget, BuildRule>> mappings) {
    return RichStream.from(mappings.entrySet())
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(),
                entry -> entry.getKey(),
                entry -> computeIfAbsent(entry.getKey(), entry.getValue())));
  }

  protected void checkRuleIsBuiltForCorrectTarget(BuildTarget arg, BuildRule rule) {
    Preconditions.checkState(
        // TODO: This should hold for flavored build targets as well.
        rule.getBuildTarget().getUnflavoredBuildTarget().equals(arg.getUnflavoredBuildTarget()),
        "Computed rule for '%s' instead of '%s'.",
        rule.getBuildTarget(),
        arg);
  }
}
