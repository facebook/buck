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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map;

/** Verifies that the {@link BuildTarget} is valid during parsing */
public class BuiltTargetVerifier {

  private static final Logger LOG = Logger.get(BuiltTargetVerifier.class);

  /** @param buildFile Absolute path to build file that contains build target being verified */
  void verifyBuildTarget(
      Cell cell,
      RuleType buildRuleType,
      Path buildFile,
      UnconfiguredBuildTarget target,
      BaseDescription<?> description,
      Map<String, Object> rawNode) {
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

    UnflavoredBuildTarget unflavoredBuildTargetViewFromRawData =
        UnflavoredBuildTargetFactory.createFromRawNode(
            cell.getRoot().getPath(), cell.getCanonicalName(), rawNode, buildFile);
    if (!unflavoredBuildTargetView.equals(unflavoredBuildTargetViewFromRawData)) {
      throw new IllegalStateException(
          String.format(
              "Inconsistent internal state, target from data: %s, expected: %s, raw data: %s",
              unflavoredBuildTargetViewFromRawData,
              unflavoredBuildTargetView,
              Joiner.on(',').withKeyValueSeparator("->").join(rawNode)));
    }
  }

  void verifyBuildTarget(
      Cell cell,
      RuleType buildRuleType,
      AbsPath buildFile,
      UnconfiguredBuildTarget target,
      BaseDescription<?> description,
      Map<String, Object> rawNode) {
    verifyBuildTarget(cell, buildRuleType, buildFile.getPath(), target, description, rawNode);
  }
}
