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

package com.facebook.buck.thrift;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Represents a thrift library target node in the action graph, providing an interface for
 * dependents to setup dependencies and include paths correctly for the thrift files this
 * library contains.
 */
public class ThriftLibrary extends AbstractBuildRule {

  private final ImmutableSortedSet<ThriftLibrary> thriftDeps;
  private final SymlinkTree includeTreeRule;
  private final ImmutableMap<Path, SourcePath> includes;

  public ThriftLibrary(
      BuildRuleParams params,
      ImmutableSortedSet<ThriftLibrary> thriftDeps,
      SymlinkTree includeTreeRule,
      ImmutableMap<Path, SourcePath> includes) {
    super(params);
    this.thriftDeps = Preconditions.checkNotNull(thriftDeps);
    this.includeTreeRule = Preconditions.checkNotNull(includeTreeRule);
    this.includes = Preconditions.checkNotNull(includes);
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  public ImmutableSortedSet<ThriftLibrary> getThriftDeps() {
    return thriftDeps;
  }

  public ImmutableMap<Path, SourcePath> getIncludes() {
    return includes;
  }

  public SymlinkTree getIncludeTreeRule() {
    return includeTreeRule;
  }

}
