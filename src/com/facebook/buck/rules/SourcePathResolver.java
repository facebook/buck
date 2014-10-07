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

package com.facebook.buck.rules;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.Map;

public class SourcePathResolver {

  @SuppressWarnings("unused") // Needed once SourcePath embed BuildTarget instead of BuildRule
  private final BuildRuleResolver ruleResolver;

  public SourcePathResolver(BuildRuleResolver ruleResolver) {
    this.ruleResolver = Preconditions.checkNotNull(ruleResolver);
  }

  public Path getPath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return (Path) sourcePath.asReference();
    }
    Preconditions.checkArgument(sourcePath instanceof BuildRuleSourcePath);
    BuildRuleSourcePath buildRuleSourcePath = (BuildRuleSourcePath) sourcePath;
    Optional<Path> resolvedPath = buildRuleSourcePath.getResolvedPath();
    if (resolvedPath.isPresent()) {
      return resolvedPath.get();
    }

    Path path = buildRuleSourcePath.getRule().getPathToOutputFile();
    if (path == null) {
      throw new HumanReadableException("No known output for: %s", buildRuleSourcePath.getRule());
    }

    return path;
  }

  public Function<SourcePath, Path> getPathFunction() {
    return new Function<SourcePath, Path>() {
      @Override
      public Path apply(SourcePath input) {
        return getPath(input);
      }
    };
  }

  public ImmutableList<Path> getAllPaths(Iterable<? extends SourcePath> sourcePaths) {
    // Maintain ordering and duplication if necessary.
    return FluentIterable.from(sourcePaths).transform(getPathFunction()).toList();
  }

  public <T> ImmutableMap<T, Path> getMappedPaths(Map<T, SourcePath> sourcePathMap) {
    ImmutableMap.Builder<T, Path> paths = ImmutableMap.builder();
    for (ImmutableMap.Entry<T, SourcePath> entry : sourcePathMap.entrySet()) {
      paths.put(entry.getKey(), getPath(entry.getValue()));
    }
    return paths.build();
  }

}
