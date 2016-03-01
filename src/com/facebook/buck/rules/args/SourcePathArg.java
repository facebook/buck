/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.util.Objects;

/**
 * An {@link Arg} which wraps a {@link SourcePath}.
 */
public class SourcePathArg extends Arg {

  private final SourcePathResolver pathResolver;
  private final SourcePath path;

  public SourcePathArg(SourcePathResolver pathResolver, SourcePath path) {
    this.pathResolver = pathResolver;
    this.path = path;
  }

  @Override
  public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
    builder.add(pathResolver.getAbsolutePath(path).toString());
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return resolver.filterBuildRuleInputs(path);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableList.of(path);
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder.setReflectively("arg", path);
  }

  @Override
  public String toString() {
    return path.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SourcePathArg)) {
      return false;
    }
    SourcePathArg that = (SourcePathArg) o;
    return Objects.equals(path, that.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(path);
  }

  public static ImmutableList<Arg> from(
      SourcePathResolver pathResolver,
      Iterable<SourcePath> paths) {
    ImmutableList.Builder<Arg> converted = ImmutableList.builder();
    for (SourcePath path : paths) {
      converted.add(new SourcePathArg(pathResolver, path));
    }
    return converted.build();
  }

  public static ImmutableList<Arg> from(SourcePathResolver pathResolver, SourcePath... paths) {
    return from(pathResolver, ImmutableList.copyOf(paths));
  }

}
