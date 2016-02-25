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

package com.facebook.buck.jvm.java.autodeps;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

final class JavaLibrarySymbolsFinder implements JavaSymbolsRule.SymbolsFinder {

  private static final Predicate<String> NOT_A_BUILT_IN_SYMBOL = new Predicate<String>() {
    @Override
    public boolean apply(String symbol) {
      // We can ignore things in java.*, but not javax.*, unfortunately since sometimes those are
      // provided by JSRs.
      return !symbol.startsWith("java.");
    }
  };

  private static final Predicate<Object> IS_PATH_SOURCE_PATH =
      Predicates.instanceOf(PathSourcePath.class);

  private final ImmutableSortedSet<SourcePath> srcs;

  private final JavaFileParser javaFileParser;

  private final boolean shouldRecordRequiredSymbols;

  JavaLibrarySymbolsFinder(
      ImmutableSortedSet<SourcePath> srcs,
      JavaFileParser javaFileParser,
      boolean shouldRecordRequiredSymbols) {
    // Avoid all the construction in the common case where all srcs are instances of PathSourcePath.
    this.srcs = Iterables.all(srcs, IS_PATH_SOURCE_PATH)
        ? srcs
        : FluentIterable.from(srcs).filter(IS_PATH_SOURCE_PATH).toSortedSet(Ordering.natural());
    this.javaFileParser = javaFileParser;
    this.shouldRecordRequiredSymbols = shouldRecordRequiredSymbols;
  }

  @Override
  public Symbols extractSymbols() {
    Set<String> providedSymbols = new HashSet<>();
    Set<String> requiredSymbols = new HashSet<>();

    for (SourcePath src : srcs) {
      // This should be enforced by the constructor.
      Preconditions.checkState(src instanceof PathSourcePath);

      PathSourcePath sourcePath = (PathSourcePath) src;
      ProjectFilesystem filesystem = sourcePath.getFilesystem();
      Path absolutePath = filesystem.resolve(sourcePath.getRelativePath());
      String code;
      try {
        code = Files.toString(absolutePath.toFile(), Charsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      JavaFileParser.JavaFileFeatures features = javaFileParser
          .extractFeaturesFromJavaCode(code);
      if (shouldRecordRequiredSymbols) {
        requiredSymbols.addAll(features.requiredSymbols);
      }

      providedSymbols.addAll(features.providedSymbols);
    }

    return new Symbols(
        providedSymbols,
        FluentIterable.from(requiredSymbols).filter(NOT_A_BUILT_IN_SYMBOL));
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("srcs", srcs)
        .setReflectively("recordRequires", shouldRecordRequiredSymbols);
  }
}
