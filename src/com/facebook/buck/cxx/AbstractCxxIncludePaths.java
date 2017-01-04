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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Collectors;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractCxxIncludePaths {

  /** Paths added with {@code -I} */
  public abstract ImmutableSet<CxxHeaders> getIPaths();

  /** Framework paths added with {@code -F} */
  public abstract ImmutableSet<FrameworkPath> getFPaths();

  /** Paths added with {@code -isystem} */
  public abstract ImmutableSet<Path> getISystemPaths();

  /** Paths added with {@code -iquote} */
  public abstract ImmutableSet<Path> getIQuotePaths();

  /**
   * Merge all the given {@link CxxIncludePaths}.
   *
   * Combinines their path lists, deduping them (keeping the earlier of the repeated instance).
   */
  public static CxxIncludePaths concat(Iterator<CxxIncludePaths> itemIter) {
    ImmutableSet.Builder<CxxHeaders> ipathBuilder = ImmutableSet.<CxxHeaders>builder();
    ImmutableSet.Builder<FrameworkPath> fpathBuilder = ImmutableSet.<FrameworkPath>builder();
    ImmutableSet.Builder<Path> isystemBuilder = ImmutableSet.<Path>builder();
    ImmutableSet.Builder<Path> iquoteBuilder = ImmutableSet.<Path>builder();

    while (itemIter.hasNext()) {
      CxxIncludePaths item = itemIter.next();
      ipathBuilder.addAll(item.getIPaths());
      fpathBuilder.addAll(item.getFPaths());
      isystemBuilder.addAll(item.getISystemPaths());
      iquoteBuilder.addAll(item.getIQuotePaths());
    }

    return CxxIncludePaths.of(
        ipathBuilder.build(),
        fpathBuilder.build(),
        isystemBuilder.build(),
        iquoteBuilder.build());
  }

  public static CxxIncludePaths empty() {
    return concat(Collections.emptyIterator());
  }

  /**
   * Build a list of compiler flag strings representing the contained paths.
   *
   * This method's parameters allow the caller to do some massaging and cleaning-up of paths.
   * @param pathResolver
   * @param pathShortener used to shorten the {@code -I} and {@code -isystem} paths
   * @param frameworkPathTransformer used to shorten/convert/transmogrify framework {@code -F} paths
   */
  public ImmutableList<String> getFlags(
      SourcePathResolver pathResolver,
      Function<Path, Path> pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(
        CxxHeaders.getArgs(
            getIPaths(),
            pathResolver,
            Optional.of(pathShortener),
            preprocessor));

    builder.addAll(
        CxxPreprocessables.IncludeType.SYSTEM.includeArgs(
            preprocessor,
            Iterables.transform(
                getISystemPaths(),
                Functions.compose(Object::toString, pathShortener))));

    builder.addAll(
        CxxPreprocessables.IncludeType.IQUOTE.includeArgs(
            preprocessor,
            Iterables.transform(
                getIQuotePaths(),
                Functions.compose(Object::toString, pathShortener))));

    builder.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-F"),
            FluentIterable.from(getFPaths())
                .transform(frameworkPathTransformer)
                .transform(Object::toString)
                .toSortedSet(Ordering.natural())));

    return builder.build();
  }

  public CxxToolFlags toToolFlags(
      SourcePathResolver resolver,
      Function<Path, Path> pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor) {
    return CxxToolFlags.explicitBuilder()
        .addAllRuleFlags(getFlags(resolver, pathShortener, frameworkPathTransformer, preprocessor))
        .build();
  }

  /**
   * Build a list of compiler flag strings representing the contained paths.
   *
   * Paths are inserted into the compiler flag list as-is, without transformation or shortening.
   */
  public ImmutableList<String> getFlags(
      SourcePathResolver pathResolver,
      Preprocessor preprocessor) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(CxxHeaders.getArgs(getIPaths(), pathResolver, Optional.empty(), preprocessor));
    builder.addAll(CxxPreprocessables.IncludeType.SYSTEM.includeArgs(
        preprocessor,
        getISystemPaths().stream().map(Object::toString).collect(Collectors.toList())));
    builder.addAll(CxxPreprocessables.IncludeType.IQUOTE.includeArgs(
        preprocessor,
        getIQuotePaths().stream().map(Object::toString).collect(Collectors.toList())));
    // TODO(elsteveogrande) gotta handle framework paths!
    return builder.build();
  }

  public CxxToolFlags toToolFlags(
      SourcePathResolver resolver,
      Preprocessor preprocessor) {
    return CxxToolFlags.explicitBuilder()
        .addAllRuleFlags(getFlags(resolver, preprocessor))
        .build();
  }

}
