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
package com.facebook.buck.rules;

import com.google.common.collect.ImmutableSortedSet;

/**
 * Describes the way in which a {@link BuildRule} will transform its declared dependencies.
 */
public interface DependencyEnhancer {

  /**
   * A {@link BuildRule} may employ graph enhancement to alter its dependencies. If it does this, it
   * must return the new dependencies via this method. The {@code inferredDeps} are derived by
   * looking at a constructor arg's public fields other than {@code deps} and finding any
   * that are or contain {@link BuildRule}s. The union of these and the {@code declaredDeps}
   * provides all the known dependencies of a rule.
   * <p>
   * Implementors of this interface will normally want to return the {@code inferredDeps} unchanged,
   * and only modify the {@code declaredDeps}. As such, the general usage pattern is:
   *
   * <pre>
   * public ImmutableSortedSet&gt;BuildRule> getEnhancedDeps(
   *     BuildRuleResolver ruleResolver,
   *     Iterable<BuildRule> declaredDeps,
   *     Iterable<BuildRule> inferredDeps) {
   *   ImmutableSortedSet.Builder&gt;BuildRule> builder = ImmutableSortedSet.naturalOrder();
   *   builder.addAll(inferredDeps);
   *
   *   ...
   *
   *   return builder.build();
   * }
   * </pre>
   *
   * @param ruleResolver that can be used to get the {@link BuildRule} that corresponds to a
   *     {@link com.facebook.buck.model.BuildTarget} if the rule for the target has already been
   *     constructed.
   * @param declaredDeps The {@link BuildRule}s declared in the {@code deps} parameter of a rule.
   * @param inferredDeps Any dependencies detected by scanning all the parameters except
   *     {@code deps} of a rule.
   * @return The {@code deps} for the {@link BuildRule} that contains this {@link BuildRule}. These
   *     may differ from the {@code deps} specified in the original build file due to graph
   *     enhancement.
   */
  ImmutableSortedSet<BuildRule> getEnhancedDeps(
      BuildRuleResolver ruleResolver,
      Iterable<BuildRule> declaredDeps,
      Iterable<BuildRule> inferredDeps);
}
