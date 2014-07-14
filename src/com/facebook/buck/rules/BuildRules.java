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
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.DirectoryTraversers;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import javax.annotation.Nullable;

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

  public static Multimap<String, BuildRule> buildRulesByTargetBasePath(Iterable<BuildRule> rules) {
    ImmutableMultimap.Builder<String, BuildRule> result = ImmutableMultimap.builder();
    for (BuildRule rule : rules) {
      result.put(rule.getBuildTarget().getBasePath(), rule);
    }
    return result.build();
  }

  /**
   * Helper function for {@link BuildRule}s to create their lists of files for caching.
   */
  public static void addInputsToSortedSet(
      @Nullable Path pathToDirectory,
      ImmutableSortedSet.Builder<Path> inputsToConsiderForCachingPurposes,
      DirectoryTraverser traverser) {
    if (pathToDirectory == null) {
      return;
    }

    Set<Path> files;
    try {
      files = DirectoryTraversers.getInstance().findFiles(pathToDirectory.toString(), traverser);
    } catch (IOException e) {
      throw new RuntimeException("Exception while traversing " + pathToDirectory + ".", e);
    }

    inputsToConsiderForCachingPurposes.addAll(files);
  }
}
