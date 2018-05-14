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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.function.Function;

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
   * target using the {@link
   * com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer} associated with this
   * instance.
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
   * Returns a parallelizer object that parallelizes if the current BuildRuleResolver supports
   * parallelism.
   */
  Parallelizer getParallelizer();

  /** Invalidates this object. All future calls will throw InvalidStateException. */
  void invalidate();

  // Convenience methods offering alternate access patterns.

  /**
   * Returns the {@code BuildRule} associated with the given {@code BuildTarget} if it is already
   * present, casting it to an expected type.
   *
   * @throws HumanReadableException if the {@code BuildRule} is not an instance of the given class.
   */
  <T> Optional<T> getRuleOptionalWithType(BuildTarget buildTarget, Class<T> cls);

  /**
   * Returns the {@code BuildRule} associated with the {@code buildTarget}.
   *
   * @throws HumanReadableException if no BuildRule is associated with the {@code BuildTarget}.
   */
  BuildRule getRule(BuildTarget buildTarget);

  /**
   * Returns the {@code BuildRule} associated with the {@code buildTarget}, casting it to an
   * expected type.
   *
   * @throws HumanReadableException if no rule is associated with the {@code BuildTarget}, or if the
   *     rule is not an instance of the given class.
   */
  <T> T getRuleWithType(BuildTarget buildTarget, Class<T> cls);

  ImmutableSortedSet<BuildRule> requireAllRules(Iterable<BuildTarget> buildTargets);

  ImmutableSortedSet<BuildRule> getAllRules(Iterable<BuildTarget> targets);

  RichStream<BuildRule> getAllRulesStream(Iterable<BuildTarget> targets);
}
