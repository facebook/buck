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
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.immutables.value.Value;

/** Encapsulates headers modeled using a {@link HeaderSymlinkTree}. */
@Value.Immutable(prehash = true)
@BuckStyleImmutable
abstract class AbstractCxxSymlinkTreeHeaders extends CxxHeaders implements RuleKeyAppendable {

  @SuppressWarnings("immutables")
  private final AtomicReference<Optional<ImmutableList<BuildRule>>> computedDeps =
      new AtomicReference<>(Optional.empty());

  @Override
  @AddToRuleKey
  public abstract CxxPreprocessables.IncludeType getIncludeType();

  @Override
  public abstract SourcePath getRoot();

  /**
   * @return the path to add to the preprocessor search path to find the includes. This defaults to
   *     the root, but can be overridden to use an alternate path.
   */
  public abstract Either<Path, SourcePath> getIncludeRoot();

  @Override
  public Optional<Path> getResolvedIncludeRoot(SourcePathResolver resolver) {
    return Optional.of(
        getIncludeRoot().transform(left -> left, right -> resolver.getAbsolutePath(right)));
  }

  @Override
  public abstract Optional<SourcePath> getHeaderMap();

  @Value.Auxiliary
  abstract ImmutableSortedMap<Path, SourcePath> getNameToPathMap();

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    builder.addSymlinkTree(getRoot(), getNameToPathMap());
  }

  /** @return all deps required by this header pack. */
  @Override
  // This has custom getDeps() logic because the way that the name to path map is added to the
  // rulekey is really slow to compute.
  public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    Stream.Builder<BuildRule> builder = Stream.builder();
    ruleFinder.getRule(getRoot()).ifPresent(builder);
    if (getIncludeRoot().isRight()) {
      ruleFinder.getRule(getIncludeRoot().getRight()).ifPresent(builder);
    }
    getHeaderMap().flatMap(ruleFinder::getRule).ifPresent(builder);

    // return a stream of the cached dependencies, or compute and store it
    return Stream.concat(
            computedDeps
                .get()
                .orElseGet(
                    () -> {
                      // We can cache the list here because getNameToPathMap() is an ImmutableMap,
                      // and if a value in the map is not in ruleFinder, an exception will be
                      // thrown. Since ruleFinder rules only increase, the output of this will never
                      // change if we do not get an exception.
                      ImmutableList.Builder<BuildRule> cachedBuilder = ImmutableList.builder();
                      getNameToPathMap()
                          .values()
                          .forEach(
                              value -> ruleFinder.getRule(value).ifPresent(cachedBuilder::add));
                      ImmutableList<BuildRule> rules = cachedBuilder.build();
                      computedDeps.set(Optional.of(rules));
                      return rules;
                    })
                .stream(),
            builder.build())
        .distinct();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    // Add stringified paths as keys. The paths in this map represent include directives rather
    // than actual on-disk locations. Also, manually wrap the beginning and end of the structure to
    // delimit the contents of this map from other fields that may have the same key.
    sink.setReflectively(".nameToPathMap", "start");
    getNameToPathMap()
        .forEach((path, sourcePath) -> sink.setReflectively(path.toString(), sourcePath));
    sink.setReflectively(".nameToPathMap", "end");
  }

  /** @return a {@link CxxHeaders} constructed from the given {@link HeaderSymlinkTree}. */
  public static CxxSymlinkTreeHeaders from(
      HeaderSymlinkTree symlinkTree, CxxPreprocessables.IncludeType includeType) {
    CxxSymlinkTreeHeaders.Builder builder = CxxSymlinkTreeHeaders.builder();
    builder.setIncludeType(includeType);
    builder.setRoot(symlinkTree.getRootSourcePath());
    builder.setNameToPathMap(symlinkTree.getLinks());

    if (includeType == CxxPreprocessables.IncludeType.LOCAL) {
      builder.setIncludeRoot(Either.ofLeft(symlinkTree.getIncludePath()));
      symlinkTree.getHeaderMapSourcePath().ifPresent(builder::setHeaderMap);
    } else {
      builder.setIncludeRoot(Either.ofRight(symlinkTree.getRootSourcePath()));
    }
    return builder.build();
  }
}
