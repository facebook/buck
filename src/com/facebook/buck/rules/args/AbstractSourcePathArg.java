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
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import org.immutables.value.Value;

/** An {@link Arg} which wraps a {@link SourcePath}. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractSourcePathArg implements Arg, HasSourcePath {

  @Override
  public abstract SourcePath getPath();

  @Override
  public void appendToCommandLine(
      ImmutableCollection.Builder<String> builder, SourcePathResolver pathResolver) {
    builder.add(pathResolver.getAbsolutePath(getPath()).toString());
  }

  public void appendToCommandLineRel(
      ImmutableCollection.Builder<String> builder, Path cellPath, SourcePathResolver pathResolver) {
    SourcePath path = getPath();
    if (path instanceof BuildTargetSourcePath
        && cellPath.equals(((BuildTargetSourcePath) path).getTarget().getCellPath())) {
      builder.add(pathResolver.getRelativePath(path).toString());
    } else {
      appendToCommandLine(builder, pathResolver);
    }
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ruleFinder.filterBuildRuleInputs(getPath());
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableList.of(getPath());
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("arg", getPath());
  }

  public static ImmutableList<Arg> from(Iterable<SourcePath> paths) {
    ImmutableList.Builder<Arg> converted = ImmutableList.builder();
    for (SourcePath path : paths) {
      converted.add(SourcePathArg.of(path));
    }
    return converted.build();
  }

  public static ImmutableList<Arg> from(SourcePath... paths) {
    return from(ImmutableList.copyOf(paths));
  }
}
