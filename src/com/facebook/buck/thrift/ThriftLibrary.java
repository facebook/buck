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

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Represents a thrift library target node in the action graph, providing an interface for
 * dependents to setup dependencies and include paths correctly for the thrift files this
 * library contains.
 */
public class ThriftLibrary extends NoopBuildRule {

  private final ImmutableSortedSet<ThriftLibrary> thriftDeps;
  private final SymlinkTree includeTreeRule;
  private final ImmutableMap<Path, SourcePath> includes;

  public ThriftLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSortedSet<ThriftLibrary> thriftDeps,
      SymlinkTree includeTreeRule,
      ImmutableMap<Path, SourcePath> includes) {
    super(params, resolver);
    this.thriftDeps = thriftDeps;
    this.includeTreeRule = includeTreeRule;
    this.includes = includes;
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
