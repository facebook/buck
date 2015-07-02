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

import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

abstract class DLinkable extends AbstractBuildRule {
  @AddToRuleKey
  private final Tool compiler;
  @AddToRuleKey
  private final ImmutableList<SourcePath> inputs;
  @AddToRuleKey
  private final ImmutableList<String> prependFlags;
  private final Path output;

  DLinkable(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<SourcePath> inputs,
      ImmutableList<String> prependFlags,
      Path output,
      Tool compiler) {
    super(params, resolver);
    this.inputs = inputs;
    this.prependFlags = prependFlags;
    this.output = output;
    this.compiler = compiler;
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
            getResolver().getAllPaths(inputs)));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }
}
