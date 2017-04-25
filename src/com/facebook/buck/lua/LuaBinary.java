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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.stream.Stream;

public class LuaBinary extends AbstractBuildRule implements BinaryBuildRule, HasRuntimeDeps {

  private final Path output;
  private final Tool wrappedBinary;
  private final String mainModule;
  private final LuaPackageComponents components;
  private final Tool lua;
  private final SourcePathRuleFinder ruleFinder;
  private final LuaConfig.PackageStyle packageStyle;

  public LuaBinary(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      Path output,
      Tool wrappedBinary,
      String mainModule,
      LuaPackageComponents components,
      Tool lua,
      LuaConfig.PackageStyle packageStyle) {
    super(buildRuleParams);
    this.ruleFinder = ruleFinder;
    Preconditions.checkArgument(!output.isAbsolute());
    this.output = output;
    this.wrappedBinary = wrappedBinary;
    this.mainModule = mainModule;
    this.components = components;
    this.lua = lua;
    this.packageStyle = packageStyle;
  }

  @Override
  public Tool getExecutableCommand() {
    return wrappedBinary;
  }

  @Override
  public boolean outputFileCanBeCopied() {
    return packageStyle != LuaConfig.PackageStyle.INPLACE;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  @VisibleForTesting
  String getMainModule() {
    return mainModule;
  }

  @VisibleForTesting
  LuaPackageComponents getComponents() {
    return components;
  }

  @VisibleForTesting
  Tool getLua() {
    return lua;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return Stream.concat(getDeclaredDeps().stream(), wrappedBinary.getDeps(ruleFinder).stream())
        .map(BuildRule::getBuildTarget);
  }
}
