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

package com.facebook.buck.python;

import com.facebook.buck.file.WriteFile;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class PythonInPlaceBinary extends PythonBinary implements HasRuntimeDeps {

  private final WriteFile script;
  private final SymlinkTree linkTree;
  private final PythonPackageComponents components;

  public PythonInPlaceBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      WriteFile script,
      SymlinkTree linkTree,
      String mainModule,
      PythonPackageComponents components) {
    super(params, resolver, mainModule, components);
    this.script = script;
    this.linkTree = linkTree;
    this.components = components;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return null;
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(new BuildTargetSourcePath(script.getBuildTarget()))
        .addInput(new BuildTargetSourcePath(linkTree.getBuildTarget()))
        .addInputs(components.getModules().values())
        .addInputs(components.getResources().values())
        .addInputs(components.getNativeLibraries().values())
        .build();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(script)
        .add(linkTree)
        .addAll(getResolver().filterBuildRuleInputs(components.getModules().values()))
        .addAll(getResolver().filterBuildRuleInputs(components.getResources().values()))
        .addAll(getResolver().filterBuildRuleInputs(components.getNativeLibraries().values()))
        .build();
  }

}
