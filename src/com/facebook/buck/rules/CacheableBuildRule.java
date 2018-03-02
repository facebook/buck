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

package com.facebook.buck.rules;

import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;

/**
 * Marks a build rule as safe to cache by {@link ActionGraphNodeCache} for incremental action graph
 * construction.
 *
 * <p>If a build rule is marked with this interface, then any build rule not directly associated
 * with a target node that can potentially end up in this build rule's subgraph must also be marked
 * with this interface.
 */
public interface CacheableBuildRule extends BuildRule {

  /**
   * Returns any build rule dependencies of this rule for the sake of caching that are not returned
   * as part of buildDeps or runtimeDeps.
   *
   * <p>Most build rules do not need to worry about this, but due to funky action graph construction
   * scenarios, some build rules do not actually parent nodes that are logically associated with
   * those build rules. E.g. {@link com.facebook.buck.cxx.CxxLibrary} is a dummy node that does not
   * directly parent the build rules associated with compiling and linking the library, which
   * caching needs to be made aware of to avoid unnecessarily recreating the associated build rules.
   */
  default SortedSet<BuildRule> getImplicitDepsForCaching() {
    return ImmutableSortedSet.of();
  }

  /**
   * Updates the build rule resolver and associated objects for this build rule.
   *
   * <p>Build rules sometimes hold field references to build rule resolvers. If this build rule is
   * to be cached, it must update its build rule resolver when a new action graph is constructed to
   * avoid leaking the entire action graph it was originally associated with.
   */
  @SuppressWarnings("unused")
  default void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {}
}
