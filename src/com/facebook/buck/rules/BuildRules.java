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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableSortedSet;

public class BuildRules {

  private BuildRules() {
    // Utility class.
  }

  public static ImmutableSortedSet<BuildRule> toBuildRulesFor(
      BuildTarget invokingBuildTarget,
      BuildRuleResolver ruleResolver,
      Iterable<BuildTarget> buildTargets,
      boolean allowNonExistentRule) {
    ImmutableSortedSet.Builder<BuildRule> buildRules = ImmutableSortedSet.naturalOrder();

    for (BuildTarget target : buildTargets) {
      BuildRule buildRule = ruleResolver.get(target);
      if (buildRule != null) {
        buildRules.add(buildRule);
      } else if (!allowNonExistentRule) {
        throw new HumanReadableException("No rule for %s found when processing %s",
            target, invokingBuildTarget.getFullyQualifiedName());
      }
    }

    return buildRules.build();
  }
}
