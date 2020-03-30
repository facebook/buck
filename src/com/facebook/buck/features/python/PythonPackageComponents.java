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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkPack;
import com.facebook.buck.core.rules.impl.Symlinks;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.step.fs.SymlinkPaths;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** All per-rule components that contribute to a Python binary. */
@BuckStyleValue
public abstract class PythonPackageComponents implements AddsToRuleKey {

  // Python modules as map of their module name to location of the source.
  @AddToRuleKey
  public abstract ImmutableListMultimap<BuildTarget, PythonComponents> getModules();

  // Resources to include in the package.
  @AddToRuleKey
  public abstract ImmutableListMultimap<BuildTarget, PythonComponents> getResources();

  // Native libraries to include in the package.
  @AddToRuleKey
  public abstract ImmutableListMultimap<BuildTarget, PythonComponents> getNativeLibraries();

  @AddToRuleKey
  public abstract Optional<SourcePath> getDefaultInitPy();

  @AddToRuleKey
  public abstract Optional<Boolean> isZipSafe();

  public void forEachInput(Consumer<SourcePath> consumer) {
    getModules().values().forEach(c -> c.forEachInput(consumer));
    getResources().values().forEach(c -> c.forEachInput(consumer));
    getNativeLibraries().values().forEach(c -> c.forEachInput(consumer));
    getDefaultInitPy().ifPresent(consumer);
  }

  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    forEachInput(sp -> ruleFinder.getRule(sp).ifPresent(deps::add));
    return deps.build();
  }

  public PythonResolvedPackageComponents resolve(SourcePathResolverAdapter resolver) {
    return ImmutablePythonResolvedPackageComponents.builder()
        .putAllModules(
            Multimaps.transformValues(
                getModules(), c -> Objects.requireNonNull(c).resolvePythonComponents(resolver)))
        .putAllResources(
            Multimaps.transformValues(
                getResources(), c -> Objects.requireNonNull(c).resolvePythonComponents(resolver)))
        .putAllNativeLibraries(
            Multimaps.transformValues(
                getNativeLibraries(),
                c -> Objects.requireNonNull(c).resolvePythonComponents(resolver)))
        .setDefaultInitPy(getDefaultInitPy().map(resolver::getAbsolutePath))
        .setZipSafe(isZipSafe())
        .build();
  }

  public Symlinks asSymlinks() {
    return new SymlinkPack(
        ImmutableList.<Symlinks>builder()
            .addAll(
                getModules().values().stream()
                    .map(PythonComponents::asSymlinks)
                    .collect(ImmutableList.toImmutableList()))
            .addAll(
                getResources().values().stream()
                    .map(PythonComponents::asSymlinks)
                    .collect(ImmutableList.toImmutableList()))
            .addAll(
                getNativeLibraries().values().stream()
                    .map(PythonComponents::asSymlinks)
                    .collect(ImmutableList.toImmutableList()))
            .build()) {

      @AddToRuleKey
      private final Optional<NonHashableSourcePathContainer> defaultInitPy =
          getDefaultInitPy().map(NonHashableSourcePathContainer::new);

      @Override
      public SymlinkPaths resolveSymlinkPaths(SourcePathResolverAdapter resolver) {
        return resolve(resolver).asSymlinkPaths();
      }

      @Override
      public void forEachSymlinkInput(Consumer<SourcePath> consumer) {
        defaultInitPy.ifPresent(nsp -> consumer.accept(nsp.getSourcePath()));
        super.forEachSymlinkInput(consumer);
      }
    };
  }

  public PythonPackageComponents withDefaultInitPy(@Nullable SourcePath emptyInit) {
    return ImmutablePythonPackageComponents.of(
        getModules(),
        getResources(),
        getNativeLibraries(),
        Optional.ofNullable(emptyInit),
        isZipSafe());
  }

  /**
   * A helper class to construct a PythonPackageComponents instance which throws human readable
   * error messages on duplicates.
   */
  public static class Builder {

    private final ListMultimap<BuildTarget, PythonComponents> modules =
        MultimapBuilder.treeKeys().arrayListValues().build();
    private final ListMultimap<BuildTarget, PythonComponents> resources =
        MultimapBuilder.treeKeys().arrayListValues().build();
    private final ListMultimap<BuildTarget, PythonComponents> nativeLibraries =
        MultimapBuilder.treeKeys().arrayListValues().build();
    private Optional<Boolean> zipSafe = Optional.empty();

    public Builder putModules(BuildTarget owner, PythonComponents components) {
      modules.put(owner, components);
      return this;
    }

    public Builder putResources(BuildTarget owner, PythonComponents components) {
      resources.put(owner, components);
      return this;
    }

    public Builder putNativeLibraries(BuildTarget owner, PythonComponents components) {
      nativeLibraries.put(owner, components);
      return this;
    }

    public Builder addZipSafe(Optional<Boolean> zipSafe) {
      if (!this.zipSafe.isPresent() && !zipSafe.isPresent()) {
        return this;
      }
      this.zipSafe = Optional.of(this.zipSafe.orElse(true) && zipSafe.orElse(true));
      return this;
    }

    public PythonPackageComponents build() {
      return ImmutablePythonPackageComponents.of(
          modules, resources, nativeLibraries, Optional.empty(), zipSafe);
    }
  }
}
