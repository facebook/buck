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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
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

public class LuaBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule, HasRuntimeDeps {

  private final Path output;
  private final Tool wrappedBinary;
  private final String mainModule;
  private final LuaPackageComponents components;
  private final Tool lua;
  private final LuaPlatform.PackageStyle packageStyle;

  public LuaBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Path output,
      Tool wrappedBinary,
      String mainModule,
      LuaPackageComponents components,
      Tool lua,
      LuaPlatform.PackageStyle packageStyle) {
    super(buildTarget, projectFilesystem, buildRuleParams);
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
    return packageStyle != LuaPlatform.PackageStyle.INPLACE;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
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
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(
            getDeclaredDeps().stream(),
            BuildableSupport.getDepsCollection(wrappedBinary, ruleFinder).stream())
        .map(BuildRule::getBuildTarget);
  }
}
