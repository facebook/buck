/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;

public interface BuildRuleResolver {
  /** @return an unmodifiable view of the rules in the index */
  Iterable<BuildRule> getBuildRules();

  /**
   * Returns the {@code BuildRule} associated with the given {@code BuildTarget} if it is already
   * present.
   */
  Optional<BuildRule> getRuleOptional(BuildTarget buildTarget);

  /**
   * Retrieve the {@code BuildRule} for the given {@code BuildTarget}. If no rules are associated
   * with the target, compute the rule using the given supplier and update the mapping.
   *
   * @param target target with which the BuildRule is associated.
   * @param mappingFunction function to compute the rule.
   * @return the current value associated with the rule
   */
  BuildRule computeIfAbsent(BuildTarget target, Function<BuildTarget, BuildRule> mappingFunction);

  /**
   * Retrieve the {@code BuildRule} for the given {@code BuildTarget}. If no rules are associated
   * with the target, compute it by transforming the {@code TargetNode} associated with this build
   * target using the {@link TargetNodeToBuildRuleTransformer} associated with this instance.
   */
  BuildRule requireRule(BuildTarget target);

  /**
   * Retrieve a piece of metadata for a target. This metadata is computed via {@link
   * MetadataProvidingDescription#createMetadata}.
   */
  <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass);

  /**
   * Adds to the index a mapping from {@code buildRule}'s target to itself and returns {@code
   * buildRule}.
   *
   * <p>Please use {@code computeIfAbsent} instead
   */
  @Deprecated
  <T extends BuildRule> T addToIndex(T buildRule);

  /**
   * An event bus to send log messages. It's just here for convenience and has no relation with how
   * BuildRuleResolver works.
   */
  @Nullable
  BuckEventBus getEventBus();

  /**
   * Returns a parallelizer object that parallelizes if the current BuildRuleResolver supports
   * parallelism.
   */
  Parallelizer getParallelizer();

  // Convenience methods offering alternate access patterns.

  /**
   * Returns the {@code BuildRule} associated with the given {@code BuildTarget} if it is already
   * present, casting it to an expected type.
   *
   * @throws HumanReadableException if the {@code BuildRule} is not an instance of the given class.
   */
  default <T> Optional<T> getRuleOptionalWithType(BuildTarget buildTarget, Class<T> cls) {
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

  /**
   * Returns the {@code BuildRule} associated with the {@code buildTarget}.
   *
   * @throws HumanReadableException if no BuildRule is associated with the {@code BuildTarget}.
   */
  default BuildRule getRule(BuildTarget buildTarget) {
    return getRuleOptional(buildTarget)
        .orElseThrow(() -> BuildRuleResolvers.unresolvableRuleException(buildTarget));
  }

  /**
   * Returns the {@code BuildRule} associated with the {@code buildTarget}, casting it to an
   * expected type.
   *
   * @throws HumanReadableException if no rule is associated with the {@code BuildTarget}, or if the
   *     rule is not an instance of the given class.
   */
  default <T> T getRuleWithType(BuildTarget buildTarget, Class<T> cls) {
    return getRuleOptionalWithType(buildTarget, cls)
        .orElseThrow(() -> BuildRuleResolvers.unresolvableRuleException(buildTarget));
  }

  default ImmutableSortedSet<BuildRule> requireAllRules(Iterable<BuildTarget> buildTargets) {
    return RichStream.from(buildTargets)
        .map(this::requireRule)
        .toImmutableSortedSet(Comparator.naturalOrder());
  }

  default ImmutableSortedSet<BuildRule> getAllRules(Iterable<BuildTarget> targets) {
    return getAllRulesStream(targets).toImmutableSortedSet(Comparator.naturalOrder());
  }

  default RichStream<BuildRule> getAllRulesStream(Iterable<BuildTarget> targets) {
    return RichStream.from(targets).map(this::getRule);
  }
}

/** Helpers for implementing BuildRuleResolver that doesn't belong in the public interface. */
class BuildRuleResolvers {
  static HumanReadableException unresolvableRuleException(BuildTarget target) {
    return new HumanReadableException("Rule for target '%s' could not be resolved.", target);
  }
}
