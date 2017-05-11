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

import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class SourcePathRuleFinder {

  private final BuildRuleResolver ruleResolver;

  public SourcePathRuleFinder(BuildRuleResolver ruleResolver) {
    this.ruleResolver = ruleResolver;
  }

  public ImmutableSet<BuildRule> filterBuildRuleInputs(Stream<? extends SourcePath> sources) {
    return RichStream.from(sources)
        .filter(BuildTargetSourcePath.class)
        .map(input -> ruleResolver.getRule(input.getTarget()))
        .collect(MoreCollectors.toImmutableSet());
  }

  public ImmutableSet<BuildRule> filterBuildRuleInputs(Iterable<? extends SourcePath> sources) {
    return filterBuildRuleInputs(StreamSupport.stream(sources.spliterator(), false));
  }

  public ImmutableSet<BuildRule> filterBuildRuleInputs(SourcePath... sources) {
    return filterBuildRuleInputs(Arrays.stream(sources));
  }

  /**
   * @return An {@link Optional} containing the {@link BuildRule} whose output {@code sourcePath}
   *     refers to, or {@code absent} if {@code sourcePath} doesn't refer to the output of a {@link
   *     BuildRule}.
   */
  public Optional<BuildRule> getRule(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return Optional.of(getRuleOrThrow((BuildTargetSourcePath) sourcePath));
    } else {
      return Optional.empty();
    }
  }

  /** @return The {@link BuildRule} whose output {@code sourcePath} refers to its output. */
  public BuildRule getRuleOrThrow(BuildTargetSourcePath sourcePath) {
    return Preconditions.checkNotNull(ruleResolver.getRule(sourcePath.getTarget()));
  }
}
