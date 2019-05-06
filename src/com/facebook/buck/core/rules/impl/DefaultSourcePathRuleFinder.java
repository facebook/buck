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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

public class DefaultSourcePathRuleFinder implements SourcePathRuleFinder {

  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver sourcePathResolver;

  DefaultSourcePathRuleFinder(BuildRuleResolver ruleResolver) {
    this.ruleResolver = ruleResolver;
    this.sourcePathResolver = DefaultSourcePathResolver.from(this);
  }

  @Override
  public ImmutableSet<BuildRule> filterBuildRuleInputs(Iterable<? extends SourcePath> sources) {
    return RichStream.from(sources)
        .map(this::getRule)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ImmutableSet<BuildRule> filterBuildRuleInputs(SourcePath... sources) {
    return filterBuildRuleInputs(Arrays.asList(sources));
  }

  @Override
  public Stream<BuildRule> filterBuildRuleInputs(Stream<SourcePath> sources) {
    return sources.map(this::getRule).filter(Optional::isPresent).map(Optional::get);
  }

  @Override
  public Stream<BuildRule> filterBuildRuleInputs(Optional<SourcePath> sourcePath) {
    return filterBuildRuleInputs(RichStream.from(sourcePath));
  }

  /**
   * @return An {@link Optional} containing the {@link BuildRule} whose output {@code sourcePath}
   *     refers to, or {@code absent} if {@code sourcePath} doesn't refer to the output of a {@link
   *     BuildRule}.
   */
  @Override
  public Optional<BuildRule> getRule(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return Optional.of(getRule((BuildTargetSourcePath) sourcePath));
    } else {
      return Optional.empty();
    }
  }

  /** @return The {@link BuildRule} whose output {@code sourcePath} refers to its output. */
  @Override
  public BuildRule getRule(BuildTargetSourcePath sourcePath) {
    return ruleResolver.getRule(sourcePath.getTarget());
  }

  @Override
  public SourcePathResolver getSourcePathResolver() {
    return sourcePathResolver;
  }
}
