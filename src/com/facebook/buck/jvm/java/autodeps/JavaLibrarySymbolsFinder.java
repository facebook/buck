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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.nio.file.Path;

final class JavaLibrarySymbolsFinder implements JavaSymbolsRule.SymbolsFinder {
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;

  private final JavaFileParser javaFileParser;

  JavaLibrarySymbolsFinder(ImmutableSortedSet<SourcePath> srcs, JavaFileParser javaFileParser) {
    // Avoid all the construction in the common case where all srcs are instances of PathSourcePath.
    this.srcs =
        Iterables.all(srcs, PathSourcePath.class::isInstance)
            ? srcs
            : srcs.stream()
                .filter(PathSourcePath.class::isInstance)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    this.javaFileParser = javaFileParser;
  }

  @Override
  public Symbols extractSymbols() {
    ImmutableSortedSet<Path> absolutePaths =
        srcs.stream()
            .map(
                src -> {
                  // This should be enforced by the constructor.
                  Preconditions.checkState(src instanceof PathSourcePath);
                  PathSourcePath sourcePath = (PathSourcePath) src;
                  ProjectFilesystem filesystem = sourcePath.getFilesystem();
                  Path absolutePath = filesystem.resolve(sourcePath.getRelativePath());
                  return absolutePath;
                })
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    return SymbolExtractor.extractSymbols(javaFileParser, absolutePaths);
  }
}
