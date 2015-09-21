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

package com.facebook.buck.go;

import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class GoBinary extends AbstractBuildRule implements BinaryBuildRule {

  @AddToRuleKey
  private final Tool linker;
  @AddToRuleKey
  private final ImmutableList<String> linkerFlags;
  @AddToRuleKey
  private final Linker cxxLinker;

  private final GoLinkable mainObject;
  private final GoSymlinkTree linkTree;

  private final Path output;

  public GoBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Linker cxxLinker,
      GoSymlinkTree linkTree,
      GoLinkable mainObject,
      Tool linker,
      ImmutableList<String> linkerFlags) {
    super(params, resolver);
    this.cxxLinker = cxxLinker;
    this.linker = linker;
    this.linkTree = linkTree;
    this.mainObject = mainObject;
    this.output = BuildTargets.getGenPath(
        params.getBuildTarget(), "%s/" + params.getBuildTarget().getShortName());
    this.linkerFlags = linkerFlags;
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(new BuildTargetSourcePath(getBuildTarget()))
        .build();
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.PACKAGING);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.<Step>of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new GoLinkStep(
            getProjectFilesystem().getRootPath(),
            cxxLinker.getCommandPrefix(getResolver()),
            linker.getCommandPrefix(getResolver()),
            linkerFlags,
            ImmutableList.<Path>of(linkTree.getRoot()),
            mainObject.getPathToOutput(),
            GoLinkStep.LinkMode.EXECUTABLE,
            output)
        );
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

}
