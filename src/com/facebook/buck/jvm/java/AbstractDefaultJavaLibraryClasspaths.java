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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.JarBuildStepsFactory.JavaDependencyInfo;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.util.Objects;
import org.immutables.builder.Builder;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDefaultJavaLibraryClasspaths {

  @Builder.Parameter
  abstract ActionGraphBuilder getActionGraphBuilder();

  abstract BuildRuleParams getBuildRuleParams();

  abstract JavaLibraryDeps getDeps();

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
    return new SourcePathRuleFinder(getActionGraphBuilder());
  }

  @Value.Lazy
  public ImmutableSortedSet<BuildRule> getFirstOrderPackageableDeps() {
    if (shouldCreateSourceOnlyAbi()) {
      // Nothing is packaged based on a source ABI rule
      return ImmutableSortedSet.of();
    }

    return getDeps().getDeps();
  }

  @Value.Lazy
  protected ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return getCompileTimeClasspathDeps().stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  ImmutableSortedSet<BuildRule> getCompileTimeClasspathDeps() {
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
    return buildRules;
  }

  @Value.Lazy
  protected ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
    return getCompileTimeClasspathUnfilteredFullDeps().stream()
        .filter(dep -> dep instanceof HasJavaAbi)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Value.Lazy
  protected ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps() {
    if (getCompileAgainstLibraryType() == CompileAgainstLibraryType.SOURCE_ONLY_ABI) {
      return getCompileTimeClasspathSourceOnlyAbiDeps();
    }

    Iterable<BuildRule> classpathFullDeps = getCompileTimeClasspathFullDeps();
    if (shouldCreateSourceOnlyAbi()) {
      classpathFullDeps =
          Iterables.concat(
              rulesRequiredForSourceOnlyAbi(classpathFullDeps), getDeps().getSourceOnlyAbiDeps());
    }

    return JavaLibraryRules.getAbiRules(getActionGraphBuilder(), classpathFullDeps);
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathSourceOnlyAbiDeps() {
    Iterable<BuildRule> classpathFullDeps = getCompileTimeClasspathFullDeps();
    if (shouldCreateSourceOnlyAbi()) {
      classpathFullDeps =
          Iterables.concat(
              rulesRequiredForSourceOnlyAbi(classpathFullDeps), getDeps().getSourceOnlyAbiDeps());
    }

    return JavaLibraryRules.getSourceOnlyAbiRules(getActionGraphBuilder(), classpathFullDeps);
  }

  @Value.Lazy
  protected Iterable<BuildRule> getCompileTimeFirstOrderDeps() {
    return Iterables.concat(
        getDeps().getDeps(),
        Objects.requireNonNull(getDeps()).getProvidedDeps(),
        Objects.requireNonNull(getDeps()).getExportedProvidedDeps());
  }

  @Value.Lazy
  protected ImmutableSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
    Iterable<BuildRule> firstOrderDeps = getCompileTimeFirstOrderDeps();

    ImmutableSet<BuildRule> rulesExportedByDependencies =
        BuildRules.getUnsortedExportedRules(firstOrderDeps);

    return RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
        .collect(ImmutableSet.toImmutableSet());
  }

  @Value.Lazy
  DefaultJavaLibraryClasspaths getSourceOnlyAbiClasspaths() {
    if (shouldCreateSourceOnlyAbi()) {
      return (DefaultJavaLibraryClasspaths) this;
    }
    return DefaultJavaLibraryClasspaths.builder()
        .from(this)
        .setShouldCreateSourceOnlyAbi(true)
        .build();
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

  private ImmutableSortedMap<BuildTarget, BuildRule> toLibraryTargetKeyedMap(
      Iterable<BuildRule> rules) {
    return RichStream.from(rules)
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(), this::toLibraryTarget, rule -> rule));
  }

  private BuildTarget toLibraryTarget(BuildRule rule) {
    return JavaAbis.isLibraryTarget(rule.getBuildTarget())
        ? rule.getBuildTarget()
        : JavaAbis.getLibraryTarget(rule.getBuildTarget());
  }

  @Value.Lazy
  public ImmutableList<JavaDependencyInfo> getDependencyInfos() {
    ImmutableList.Builder<JavaDependencyInfo> builder = ImmutableList.builder();

    ImmutableSortedMap<BuildTarget, BuildRule> abiDeps =
        toLibraryTargetKeyedMap(getCompileTimeClasspathAbiDeps());
    ImmutableSortedMap<BuildTarget, BuildRule> sourceOnlyAbiDeps =
        toLibraryTargetKeyedMap(getSourceOnlyAbiClasspaths().getCompileTimeClasspathDeps());

    for (BuildRule compileTimeDep : getCompileTimeClasspathDeps()) {
      Preconditions.checkState(compileTimeDep instanceof HasJavaAbi);

      BuildTarget compileTimeDepLibraryTarget = toLibraryTarget(compileTimeDep);

      boolean requiredForSourceOnlyAbi = sourceOnlyAbiDeps.containsKey(compileTimeDepLibraryTarget);
      boolean isAbiDep = abiDeps.containsKey(compileTimeDepLibraryTarget);

      SourcePath compileTimeSourcePath = compileTimeDep.getSourcePathToOutput();

      // Some deps might not actually contain any source files. In that case, they have no output.
      // Just skip them.
      if (compileTimeSourcePath == null) {
        continue;
      }

      SourcePath abiClasspath;
      if (isAbiDep) {
        abiClasspath =
            Objects.requireNonNull(
                abiDeps.get(compileTimeDepLibraryTarget).getSourcePathToOutput());
      } else {
        abiClasspath = compileTimeSourcePath;
      }

      builder.add(
          new JavaDependencyInfo(compileTimeSourcePath, abiClasspath, requiredForSourceOnlyAbi));
    }

    return builder.build();
  }
}
