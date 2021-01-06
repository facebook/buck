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

package com.facebook.buck.parser.detector;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** An utility to detect target configuration when it is not specified otherwise. */
public class TargetConfigurationDetector {
  /** A matcher part of the rule */
  interface Matcher {
    boolean matches(UnconfiguredTargetNode node);
  }

  private final ImmutableList<Pair<Matcher, TargetConfiguration>> matchers;

  TargetConfigurationDetector(ImmutableList<Pair<Matcher, TargetConfiguration>> matchers) {
    this.matchers = matchers;
  }

  /** Find first matching configuration, or return empty if no rules match the target. */
  public Optional<TargetConfiguration> detectTargetConfiguration(
      UnconfiguredTargetNode unconfiguredBuildTarget) {
    for (Pair<Matcher, TargetConfiguration> pair : matchers) {
      if (pair.getFirst().matches(unconfiguredBuildTarget)) {
        return Optional.of(pair.getSecond());
      }
    }

    return Optional.empty();
  }

  /** Matcher implementation which matches the package prefix */
  static class SpecMatcher implements Matcher {
    static final String TYPE = "target";

    private final SimplePackageSpec simplePackageSpec;

    public SpecMatcher(SimplePackageSpec simplePackageSpec) {
      this.simplePackageSpec = simplePackageSpec;
    }

    @Override
    public boolean matches(UnconfiguredTargetNode node) {
      return simplePackageSpec.matches(node.getBuildTarget());
    }

    @Override
    public String toString() {
      return simplePackageSpec.toString();
    }
  }
}
