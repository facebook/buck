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

package com.facebook.buck.rules.args;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Set;
import java.util.TreeSet;

/**
 * An {@link Arg} implementation which resolves to all files under a directory represented by a
 * {@link SourcePath}.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractGlobArg extends Arg {

  @Value.Parameter
  abstract SourcePathResolver getPathResolver();

  @Value.Parameter
  abstract SourcePath getRoot();

  @Value.Parameter
  abstract String getPattern();

  /**
   * Find all files under the given directory and add them to the command line builder.
   */
  @Override
  public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
    final Set<Path> paths = new TreeSet<>();
    ProjectFilesystem filesystem = getPathResolver().getFilesystem(getRoot());
    PathMatcher matcher =  // NOPMD
        filesystem.getRootPath().getFileSystem().getPathMatcher("glob:" + getPattern());
    try {
      paths.addAll(
          filesystem.getFilesUnderPath(
              getPathResolver().getRelativePath(getRoot()),
              matcher::matches));
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
    builder.addAll(
        FluentIterable.from(paths)
            .transform(filesystem::resolve)
            .transform(Object::toString));
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return resolver.filterBuildRuleInputs(getRoot());
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableList.of(getRoot());
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink
        .setReflectively("root", getRoot())
        .setReflectively("glob", getPattern());
  }

}
