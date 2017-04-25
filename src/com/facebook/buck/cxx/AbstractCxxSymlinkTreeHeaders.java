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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Encapsulates headers modeled using a {@link HeaderSymlinkTree}. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxSymlinkTreeHeaders extends CxxHeaders {

  @Override
  public abstract CxxPreprocessables.IncludeType getIncludeType();

  @Override
  public abstract SourcePath getRoot();

  /**
   * @return the path to add to the preprocessor search path to find the includes. This defaults to
   *     the root, but can be overridden to use an alternate path.
   */
  @Override
  @Value.Default
  public SourcePath getIncludeRoot() {
    return getRoot();
  }

  @Override
  public abstract Optional<SourcePath> getHeaderMap();

  abstract ImmutableMap<Path, SourcePath> getNameToPathMap();

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    builder.addSymlinkTree(getRoot(), getNameToPathMap());
  }

  /** @return all deps required by this header pack. */
  @Override
  public Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    deps.addAll(ruleFinder.filterBuildRuleInputs(getNameToPathMap().values()));
    deps.addAll(ruleFinder.filterBuildRuleInputs(getRoot()));
    deps.addAll(ruleFinder.filterBuildRuleInputs(getIncludeRoot()));
    deps.addAll(ruleFinder.filterBuildRuleInputs(OptionalCompat.asSet(getHeaderMap())));
    return deps.build();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("type", getIncludeType());
    for (Path path : ImmutableSortedSet.copyOf(getNameToPathMap().keySet())) {
      SourcePath source = getNameToPathMap().get(path);
      sink.setReflectively("include(" + path.toString() + ")", source);
    }
  }

  /** @return a {@link CxxHeaders} constructed from the given {@link HeaderSymlinkTree}. */
  public static CxxSymlinkTreeHeaders from(
      HeaderSymlinkTree symlinkTree, CxxPreprocessables.IncludeType includeType) {
    CxxSymlinkTreeHeaders.Builder builder = CxxSymlinkTreeHeaders.builder();
    builder.setIncludeType(includeType);
    builder.setRoot(
        new ExplicitBuildTargetSourcePath(symlinkTree.getBuildTarget(), symlinkTree.getRoot()));

    if (includeType == CxxPreprocessables.IncludeType.LOCAL) {
      builder.setIncludeRoot(
          new ExplicitBuildTargetSourcePath(
              symlinkTree.getBuildTarget(), symlinkTree.getIncludePath()));
      if (symlinkTree.getHeaderMap().isPresent()) {
        builder.setHeaderMap(
            new ExplicitBuildTargetSourcePath(
                symlinkTree.getBuildTarget(), symlinkTree.getHeaderMap().get()));
      }
    } else {
      builder.setIncludeRoot(
          new ExplicitBuildTargetSourcePath(symlinkTree.getBuildTarget(), symlinkTree.getRoot()));
    }
    builder.putAllNameToPathMap(symlinkTree.getLinks());
    return builder.build();
  }
}
