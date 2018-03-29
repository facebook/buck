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

import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
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
  public CompileAgainstLibraryType getCompileAgainstLibraryType() {
    return CompileAgainstLibraryType.FULL;
  }

  @Value.Default
  public boolean shouldCreateSourceOnlyAbi() {
    return false;
  }

  @Value.Lazy
  SourcePathRuleFinder getSourcePathRuleFinder() {
    return new SourcePathRuleFinder(getBuildRuleResolver());
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getFirstOrderPackageableDeps() {
    if (shouldCreateSourceOnlyAbi()) {
      // Nothing is packaged based on a source ABI rule
      return ImmutableSortedSet.of();
    }

    return ImmutableSortedSet.copyOf(getAllFirstOrderNonProvidedDeps());
  }

  @Value.Lazy
  ImmutableList<BuildRule> getAllFirstOrderNonProvidedDeps() {
    return ImmutableList.copyOf(
        Iterables.concat(
            Preconditions.checkNotNull(getDeps()).getDeps(),
            getConfiguredCompiler().getDeclaredDeps(getSourcePathRuleFinder())));
  }

  @Value.Lazy
  public ImmutableList<BuildRule> getNonClasspathDeps() {
    // TODO(jkeljo): When creating source-only ABIs, *some* non-classpath deps can be omitted
    // (basically anything that's not either source, resources, or a source-only-ABI-compatible
    // annotation processor).
    return ImmutableList.copyOf(
        Iterables.concat(
            Sets.difference(getBuildRuleParams().getBuildDeps(), getCompileTimeClasspathFullDeps()),
            Sets.difference(
                getCompileTimeClasspathUnfilteredFullDeps(), getCompileTimeClasspathFullDeps())));
  }

  @Value.Lazy
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    ImmutableSortedSet<BuildRule> buildRules;

    switch (getCompileAgainstLibraryType()) {
      case FULL:
        buildRules = getCompileTimeClasspathFullDeps();
        break;
      case ABI:
      case SOURCE_ONLY_ABI:
        buildRules = getCompileTimeClasspathAbiDeps();
        break;
      default:
        throw new IllegalStateException();
    }

    return buildRules
        .stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
    return getCompileTimeClasspathUnfilteredFullDeps()
        .stream()
        .filter(dep -> dep instanceof HasJavaAbi)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps() {
    if (getCompileAgainstLibraryType() == CompileAgainstLibraryType.SOURCE_ONLY_ABI) {
      return getCompileTimeClasspathSourceOnlyAbiDeps();
    }

    Iterable<BuildRule> classpathFullDeps = getCompileTimeClasspathFullDeps();
    if (shouldCreateSourceOnlyAbi()) {
      classpathFullDeps =
          Iterables.concat(
              rulesRequiredForSourceOnlyAbi(classpathFullDeps), getDeps().getSourceOnlyAbiDeps());
    }

    return JavaLibraryRules.getAbiRules(getBuildRuleResolver(), classpathFullDeps);
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathSourceOnlyAbiDeps() {
    Iterable<BuildRule> classpathFullDeps = getCompileTimeClasspathFullDeps();
    if (shouldCreateSourceOnlyAbi()) {
      classpathFullDeps =
          Iterables.concat(
              rulesRequiredForSourceOnlyAbi(classpathFullDeps), getDeps().getSourceOnlyAbiDeps());
    }

    return JavaLibraryRules.getSourceOnlyAbiRules(getBuildRuleResolver(), classpathFullDeps);
  }

  @Value.Lazy
  public ZipArchiveDependencySupplier getAbiClasspath() {
    return new ZipArchiveDependencySupplier(
        getSourcePathRuleFinder(),
        getCompileTimeClasspathAbiDeps()
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  @Value.Lazy
  Iterable<BuildRule> getCompileTimeFirstOrderDeps() {
    return Iterables.concat(
        getAllFirstOrderNonProvidedDeps(),
        Preconditions.checkNotNull(getDeps()).getProvidedDeps(),
        Preconditions.checkNotNull(getDeps()).getExportedProvidedDeps());
  }

  @Value.Lazy
  ImmutableSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
    Iterable<BuildRule> firstOrderDeps = getCompileTimeFirstOrderDeps();

    ImmutableSet<BuildRule> rulesExportedByDependencies =
        BuildRules.getUnsortedExportedRules(firstOrderDeps);

    return RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
        .collect(ImmutableSet.toImmutableSet());
  }

  private Iterable<BuildRule> rulesRequiredForSourceOnlyAbi(Iterable<BuildRule> rules) {
    return RichStream.from(rules)
        .filter(
            rule -> {
              if (rule instanceof MaybeRequiredForSourceOnlyAbi) {
                MaybeRequiredForSourceOnlyAbi maybeRequired = (MaybeRequiredForSourceOnlyAbi) rule;
                return maybeRequired.getRequiredForSourceOnlyAbi();
              }

              return false;
            })
        .toOnceIterable();
  }
}
