/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public class SourcePathRuleFinder {

  private final BuildRuleResolver ruleResolver;

  public SourcePathRuleFinder(BuildRuleResolver ruleResolver) {
    this.ruleResolver = ruleResolver;
  }

  public final Function<SourcePath, Stream<BuildRule>> FILTER_BUILD_RULE_INPUTS =
      path -> Optionals.toStream(getRule(path));

  public ImmutableSet<BuildRule> filterBuildRuleInputs(Iterable<? extends SourcePath> sources) {
    return RichStream.from(sources)
        .flatMap(FILTER_BUILD_RULE_INPUTS)
        .collect(ImmutableSet.toImmutableSet());
  }

  public ImmutableSet<BuildRule> filterBuildRuleInputs(SourcePath... sources) {
    return RichStream.of(sources)
        .flatMap(FILTER_BUILD_RULE_INPUTS)
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * @return An {@link Optional} containing the {@link BuildRule} whose output {@code sourcePath}
   *     refers to, or {@code absent} if {@code sourcePath} doesn't refer to the output of a {@link
   *     BuildRule}.
   */
  public Optional<BuildRule> getRule(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return Optional.of(getRule((BuildTargetSourcePath) sourcePath));
    } else {
      return Optional.empty();
    }
  }

  /** @return The {@link BuildRule} whose output {@code sourcePath} refers to its output. */
  public BuildRule getRule(BuildTargetSourcePath sourcePath) {
    return ruleResolver.getRule(sourcePath.getTarget());
  }
}
