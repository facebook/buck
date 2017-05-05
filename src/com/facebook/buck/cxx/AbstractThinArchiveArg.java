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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/**
 * An {@link Arg} implementation that represents a thin archive. As opposed to normal archives, thin
 * archives also need to propagate their inputs as build time deps to consumers, since they only
 * embed relative paths to the inputs, which need to exist at build time.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractThinArchiveArg implements Arg, HasSourcePath {

  @Override
  public abstract SourcePath getPath();

  protected abstract ImmutableList<SourcePath> getContents();

  @Override
  public void appendToCommandLine(
      ImmutableCollection.Builder<String> builder, SourcePathResolver pathResolver) {
    builder.add(pathResolver.getAbsolutePath(getPath()).toString());
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableList.<SourcePath>builder().add(getPath()).addAll(getContents()).build();
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ruleFinder.filterBuildRuleInputs(getInputs());
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("archive", getPath()).setReflectively("inputs", getContents());
  }
}
