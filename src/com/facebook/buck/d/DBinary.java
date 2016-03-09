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

package com.facebook.buck.d;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * BinaryBuildRule implementation for D binaries.
 */
public class DBinary extends AbstractBuildRule implements
    BinaryBuildRule,
    HasRuntimeDeps {

  private final Tool executable;
  private final Path output;

  public DBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool executable,
      Path output) {
    super(params, resolver);
    this.executable = executable;
    this.output = output;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Tool getExecutableCommand() {
    return executable;
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    // Return the actual executable as a runtime dependency.
    // Without this, the file is not written when we get a cache hit.
    return ImmutableSortedSet.<BuildRule>naturalOrder()
      .addAll(executable.getDeps(getResolver()))
      .build();
  }
}
