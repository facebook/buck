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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Comparator;
import java.util.Optional;

public abstract class AbstractBuildRuleResolver implements BuildRuleResolver {
  @Override
  public <T> Optional<T> getRuleOptionalWithType(BuildTarget buildTarget, Class<T> cls) {
    return getRuleOptional(buildTarget)
        .map(
            rule -> {
              if (cls.isInstance(rule)) {
                return cls.cast(rule);
              } else {
                throw new HumanReadableException(
                    "Rule for target '%s' is present but not of expected type %s (got %s)",
                    buildTarget, cls, rule.getClass());
              }
            });
  }

  @Override
  public BuildRule getRule(BuildTarget buildTarget) {
    return getRuleOptional(buildTarget).orElseThrow(() -> unresolvableRuleException(buildTarget));
  }

  @Override
  public <T> T getRuleWithType(BuildTarget buildTarget, Class<T> cls) {
    return getRuleOptionalWithType(buildTarget, cls)
        .orElseThrow(() -> unresolvableRuleException(buildTarget));
  }

  @Override
  public ImmutableSortedSet<BuildRule> requireAllRules(Iterable<BuildTarget> buildTargets) {
    return RichStream.from(buildTargets)
        .map(this::requireRule)
        .toImmutableSortedSet(Comparator.naturalOrder());
  }

  @Override
  public ImmutableSortedSet<BuildRule> getAllRules(Iterable<BuildTarget> targets) {
    return getAllRulesStream(targets).toImmutableSortedSet(Comparator.naturalOrder());
  }

  @Override
  public RichStream<BuildRule> getAllRulesStream(Iterable<BuildTarget> targets) {
    return RichStream.from(targets).map(this::getRule);
  }

  private static HumanReadableException unresolvableRuleException(BuildTarget target) {
    return new HumanReadableException("Rule for target '%s' could not be resolved.", target);
  }
}
