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
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

abstract class DLinkable extends AbstractBuildRule {
  @AddToRuleKey
  private final Tool compiler;
  @AddToRuleKey
  private final ImmutableSet<Path> inputPaths;
  @AddToRuleKey
  private final ImmutableList<String> prependFlags;
  private final Path output;

  DLinkable(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<SourcePath> srcs,
      ImmutableList<String> prependFlags,
      Path output,
      Tool compiler) {
    super(params, resolver);

    ImmutableSet.Builder<Path> pathBuilder = ImmutableSet.builder();
    pathBuilder.addAll(getResolver().getAllPaths(srcs));
    for (BuildRule dep : getDeps()) {
      Path depPath = dep.getPathToOutputFile();
      if (depPath != null) {
        pathBuilder.add(depPath);
      }
    }

    inputPaths = pathBuilder.build();
    this.output = output;
    this.prependFlags = prependFlags;
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
            prependFlags,
            output,
            inputPaths));
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.<Path>of();
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }
}
