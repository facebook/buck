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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class CxxLink extends AbstractBuildRule {

  private final Path linker;
  private final Path output;
  private final ImmutableList<SourcePath> inputs;
  private final ImmutableList<String> args;

  public CxxLink(
      BuildRuleParams params,
      Path linker,
      Path output,
      ImmutableList<SourcePath> inputs,
      ImmutableList<String> args) {
    super(params);
    this.linker = Preconditions.checkNotNull(linker);
    this.output = Preconditions.checkNotNull(output);
    this.inputs = Preconditions.checkNotNull(inputs);
    this.args = Preconditions.checkNotNull(args);
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return SourcePaths.toPaths(inputs);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setInput("linker", linker)
        .set("output", output.toString())
        .set("args", args);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new CxxLinkStep(
            linker,
            output,
            args));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  public Path getLinker() {
    return linker;
  }

  public Path getOutput() {
    return output;
  }

  public ImmutableList<String> getArgs() {
    return args;
  }

}
