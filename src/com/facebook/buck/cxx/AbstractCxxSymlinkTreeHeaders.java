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

import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

/** Encapsulates headers modeled using a {@link HeaderSymlinkTree}. */
@Value.Immutable(prehash = true)
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

  @Value.Auxiliary
  abstract ImmutableMap<Path, SourcePath> getNameToPathMap();

  /** The build target that this object is modeling. */
  abstract BuildTarget getBuildTarget();

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    builder.addSymlinkTree(getRoot(), getNameToPathMap());
  }

  /** @return all deps required by this header pack. */
  @Override
  public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    Stream.Builder<BuildRule> builder = Stream.builder();
    getNameToPathMap().values().forEach(value -> ruleFinder.getRule(value).ifPresent(builder));
    ruleFinder.getRule(getRoot()).ifPresent(builder);
    ruleFinder.getRule(getIncludeRoot()).ifPresent(builder);
    getHeaderMap().flatMap(ruleFinder::getRule).ifPresent(builder);
    return builder.build().distinct();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("type", getIncludeType());
    getNameToPathMap()
        .entrySet()
        .stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .forEachOrdered(
            entry ->
                sink.setReflectively(
                    "include(" + entry.getKey().toString() + ")", entry.getValue()));
  }

  /** @return a {@link CxxHeaders} constructed from the given {@link HeaderSymlinkTree}. */
  public static CxxSymlinkTreeHeaders from(
      HeaderSymlinkTree symlinkTree, CxxPreprocessables.IncludeType includeType) {
    CxxSymlinkTreeHeaders.Builder builder = CxxSymlinkTreeHeaders.builder();
    builder.setBuildTarget(symlinkTree.getBuildTarget());
    builder.setIncludeType(includeType);
    builder.setRoot(
        ExplicitBuildTargetSourcePath.of(
            symlinkTree.getBuildTarget(),
            symlinkTree.getProjectFilesystem().relativize(symlinkTree.getRoot())));

    if (includeType == CxxPreprocessables.IncludeType.LOCAL) {
      builder.setIncludeRoot(
          ExplicitBuildTargetSourcePath.of(
              symlinkTree.getBuildTarget(), symlinkTree.getIncludePath()));
      if (symlinkTree.getHeaderMap().isPresent()) {
        builder.setHeaderMap(
            ExplicitBuildTargetSourcePath.of(
                symlinkTree.getBuildTarget(), symlinkTree.getHeaderMap().get()));
      }
    } else {
      builder.setIncludeRoot(
          ExplicitBuildTargetSourcePath.of(
              symlinkTree.getBuildTarget(),
              symlinkTree.getProjectFilesystem().relativize(symlinkTree.getRoot())));
    }
    builder.putAllNameToPathMap(symlinkTree.getLinks());
    return builder.build();
  }
}
