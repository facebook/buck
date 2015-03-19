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

import com.facebook.buck.cxx.Tool;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class DBinary extends AbstractBuildRule implements BinaryBuildRule {

  @AddToRuleKey
  private final Tool compiler;
  private final Path output;
  @AddToRuleKey
  private final ImmutableList<SourcePath> inputs;

  public DBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<SourcePath> inputs,
      Tool compiler) {
    super(params, resolver);
    BuildTarget target = params.getBuildTarget();
    this.output = BuildTargets.getBinPath(target, "%s/" + target.getShortName());
    this.inputs = inputs;
    this.compiler = compiler;
  }


  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of(
        new MakeCleanDirectoryStep(output.getParent()),
        new DCompileStep(
            compiler.getCommandPrefix(getResolver()),
            /* flags */ ImmutableList.<String>of(),
            output,
            getResolver().getAllPaths(inputs)));
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(inputs);
  }

  @Override
  public ImmutableList<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    return ImmutableList.of(projectFilesystem.resolve(output).toString());
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.PACKAGING);
  }
}
