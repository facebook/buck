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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableSortedSet;

/** Builder suitable for generating the dependency list of a build rule. */
public class DepsBuilder {
  private final ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
  private final SourcePathRuleFinder ruleFinder;

  public DepsBuilder(SourcePathRuleFinder ruleFinder) {
    this.ruleFinder = ruleFinder;
  }

  public ImmutableSortedSet<BuildRule> build() {
    return builder.build();
  }

  private DepsBuilder add(Tool tool) {
    builder.addAll(tool.getDeps(ruleFinder));
    return this;
  }

  public DepsBuilder add(CxxSource source) {
    return add(source.getPath());
  }

  public DepsBuilder add(SourcePath sourcePath) {
    builder.addAll(ruleFinder.filterBuildRuleInputs(sourcePath));
    return this;
  }

  public DepsBuilder add(BuildRule buildRule) {
    builder.add(buildRule);
    return this;
  }

  public DepsBuilder add(PreprocessorDelegate delegate) {
    add(delegate.getPreprocessor());
    return this;
  }

  public DepsBuilder add(CompilerDelegate delegate) {
    add(delegate.getCompiler());
    return this;
  }
}
