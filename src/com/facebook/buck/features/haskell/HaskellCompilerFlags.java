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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.immutables.value.Value;

/** Common haskell compilation flags shared by haskell_library and haddock_library. */
@BuckStyleValueWithBuilder
abstract class HaskellCompilerFlags implements AddsToRuleKey {

  /** Additional flags passed to Haskell compiler */
  @AddToRuleKey
  public abstract ImmutableList<String> getAdditionalFlags();

  /** Additional flags passed to C preprocessor. */
  @AddToRuleKey
  @Value.Default
  public CxxToolFlags getAdditionalPreprocessorFlags() {
    return CxxToolFlags.of();
  }

  /** @see PreprocessorFlags.getIncludes */
  @AddToRuleKey
  public abstract ImmutableList<CxxHeaders> getIncludes();

  /** @see PreprocessorFlags.getFrameworkPaths */
  @AddToRuleKey
  public abstract ImmutableList<FrameworkPath> getFrameworkPaths();

  /** Haddock interface files (not .hi files) of transitive dependencies. */
  @AddToRuleKey
  @Value.NaturalOrder
  public abstract ImmutableSortedSet<SourcePath> getHaddockInterfaces();

  /** Haskell compilation flags exported from packages. */
  @AddToRuleKey
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, ImmutableList<String>> getPackageExportedFlags();

  /** Packages providing modules that modules from this compilation can directly import. */
  @AddToRuleKey
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, HaskellPackage> getExposedPackages();

  /**
   * Packages that are transitively used by the exposed packages. Modules in this compilation cannot
   * import modules from these.
   */
  @AddToRuleKey
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, HaskellPackage> getPackages();

  public final PreprocessorFlags getPreprocessorFlags() {
    return PreprocessorFlags.builder()
        .addAllIncludes(getIncludes())
        .addAllFrameworkPaths(getFrameworkPaths())
        .setOtherFlags(getAdditionalPreprocessorFlags())
        .build();
  }

  /** @return the arguments to pass to the compiler to build against package dependencies. */
  public final Iterable<String> getPackageFlags(
      HaskellPlatform platform, SourcePathResolverAdapter resolver) {
    String exposePackage = platform.supportExposePackage() ? "-expose-package" : "-package";
    Set<String> packageDbs = new TreeSet<>();
    Set<String> hidden = new TreeSet<>();
    Set<String> exposed = new TreeSet<>();

    for (HaskellPackage haskellPackage : getPackages().values()) {
      packageDbs.add(resolver.getAbsolutePath(haskellPackage.getPackageDb()).toString());
      hidden.add(
          String.format(
              "%s-%s", haskellPackage.getInfo().getName(), haskellPackage.getInfo().getVersion()));
    }

    for (HaskellPackage haskellPackage : getExposedPackages().values()) {
      packageDbs.add(resolver.getAbsolutePath(haskellPackage.getPackageDb()).toString());
      exposed.add(
          String.format(
              "%s-%s", haskellPackage.getInfo().getName(), haskellPackage.getInfo().getVersion()));
    }

    // We add all package DBs, and explicit expose or hide packages depending on whether they are
    // exposed or not.  This allows us to support setups that either add `-hide-all-packages` or
    // not.
    return ImmutableList.<String>builder()
        .addAll(Iterables.concat(getPackageExportedFlags().values()))
        .addAll(MoreIterables.zipAndConcat(Iterables.cycle("-package-db"), packageDbs))
        .addAll(MoreIterables.zipAndConcat(Iterables.cycle(exposePackage), exposed))
        .addAll(MoreIterables.zipAndConcat(Iterables.cycle("-hide-package"), hidden))
        .build();
  }

  public final Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(getPreprocessorFlags().getDeps(ruleFinder))
        .addAll(
            Stream.of(getExposedPackages(), getPackages())
                .flatMap(packageMap -> packageMap.values().stream())
                .flatMap(pkg -> pkg.getDeps(ruleFinder))
                .iterator())
        .build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableHaskellCompilerFlags.Builder {}
}
