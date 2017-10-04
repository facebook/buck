/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Objects;
import org.immutables.builder.Builder;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDefaultJavaLibraryClasspaths {

  @Builder.Parameter
  abstract BuildRuleResolver getBuildRuleResolver();

  abstract BuildRuleParams getBuildRuleParams();

  abstract JavaLibraryDeps getDeps();

  abstract ConfiguredCompiler getConfiguredCompiler();

  @Value.Default
  public boolean shouldCompileAgainstAbis() {
    return false;
  }

  @Value.Lazy
  SourcePathRuleFinder getSourcePathRuleFinder() {
    return new SourcePathRuleFinder(getBuildRuleResolver());
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getFirstOrderPackageableDeps() {
    return ImmutableSortedSet.copyOf(
        Iterables.concat(
            Preconditions.checkNotNull(getDeps()).getDeps(),
            getConfiguredCompiler().getDeclaredDeps(getSourcePathRuleFinder())));
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getNonClasspathDeps() {
    return ImmutableSortedSet.copyOf(
        Iterables.concat(
            Sets.difference(getBuildRuleParams().getBuildDeps(), getCompileTimeClasspathFullDeps()),
            Sets.difference(
                getCompileTimeClasspathUnfilteredFullDeps(), getCompileTimeClasspathFullDeps())));
  }

  @Value.Lazy
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    ImmutableSortedSet<BuildRule> buildRules =
        shouldCompileAgainstAbis()
            ? getCompileTimeClasspathAbiDeps()
            : getCompileTimeClasspathFullDeps();

    return buildRules
        .stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
    return getCompileTimeClasspathUnfilteredFullDeps()
        .stream()
        .filter(dep -> dep instanceof HasJavaAbi)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps() {
    return JavaLibraryRules.getAbiRules(getBuildRuleResolver(), getCompileTimeClasspathFullDeps());
  }

  @Value.Lazy
  public ZipArchiveDependencySupplier getAbiClasspath() {
    return new ZipArchiveDependencySupplier(
        getSourcePathRuleFinder(),
        getCompileTimeClasspathAbiDeps()
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet()));
  }

  @Value.Lazy
  ImmutableSortedSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
    Iterable<BuildRule> firstOrderDeps =
        Iterables.concat(
            getFirstOrderPackageableDeps(),
            Preconditions.checkNotNull(getDeps()).getProvidedDeps());

    ImmutableSortedSet<BuildRule> rulesExportedByDependencies =
        BuildRules.getExportedRules(firstOrderDeps);

    return RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
        .collect(MoreCollectors.toImmutableSortedSet());
  }
}
