/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.collect.ImmutableSet;

/** Verify flavored targets against flavored descriptor. */
public class FlavoredVerifier {
  private static final Logger LOG = Logger.get(FlavoredVerifier.class);

  /** Verify flavored target against flavored descriptor. */
  public static void verify(
      UnconfiguredBuildTarget target, RuleType buildRuleType, BaseDescription<?> description) {
    UnflavoredBuildTarget unflavoredBuildTargetView = target.getUnflavoredBuildTarget();

    if (target.isFlavored()) {
      if (description instanceof Flavored) {
        // TODO(nga): use proper target configuration
        if (!((Flavored) description)
            .hasFlavors(
                ImmutableSet.copyOf(target.getFlavors().getSet()),
                UnconfiguredTargetConfiguration.INSTANCE)) {
          throw UnexpectedFlavorException.createWithSuggestions((Flavored) description, target);
        }
      } else {
        LOG.warn(
            "Target %s (type %s) must implement the Flavored interface "
                + "before we can check if it supports flavors: %s",
            unflavoredBuildTargetView, buildRuleType, target.getFlavors());
        ImmutableSet<String> invalidFlavorsStr =
            target.getFlavors().getSet().stream()
                .map(Flavor::toString)
                .collect(ImmutableSet.toImmutableSet());
        String invalidFlavorsDisplayStr = String.join(", ", invalidFlavorsStr);
        throw new HumanReadableException(
            "The following flavor(s) are not supported on target %s:\n"
                + "%s.\n\n"
                + "Please try to remove them when referencing this target.",
            unflavoredBuildTargetView, invalidFlavorsDisplayStr);
      }
    }
  }
}
