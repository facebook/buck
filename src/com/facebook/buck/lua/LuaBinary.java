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

package com.facebook.buck.lua;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class LuaBinary
    extends AbstractBuildRule
    implements BinaryBuildRule, HasRuntimeDeps {

  private final Path output;
  private final Tool wrappedBinary;
  private final String mainModule;
  private final LuaPackageComponents components;
  private final Tool lua;
  private final SourcePathRuleFinder ruleFinder;

  public LuaBinary(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Path output,
      Tool wrappedBinary,
      String mainModule,
      LuaPackageComponents components,
      Tool lua) {
    super(buildRuleParams, resolver);
    this.ruleFinder = ruleFinder;
    Preconditions.checkArgument(!output.isAbsolute());
    this.output = output;
    this.wrappedBinary = wrappedBinary;
    this.mainModule = mainModule;
    this.components = components;
    this.lua = lua;
  }

  @Override
  public Tool getExecutableCommand() {
    return wrappedBinary;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  protected Path getBinPath() {
    return output;
  }

  @Override
  public Path getPathToOutput() {
    return getBinPath();
  }

  @VisibleForTesting
  protected String getMainModule() {
    return mainModule;
  }

  @VisibleForTesting
  protected LuaPackageComponents getComponents() {
    return components;
  }

  @VisibleForTesting
  protected Tool getLua() {
    return lua;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(getDeclaredDeps())
        .addAll(wrappedBinary.getDeps(ruleFinder))
        .build();
  }

}
