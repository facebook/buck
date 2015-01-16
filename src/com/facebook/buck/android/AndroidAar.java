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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class AndroidAar extends AbstractBuildRule {

  private final Path pathToOutputFile;

  public AndroidAar(
      BuildRuleParams params,
      SourcePathResolver resolver) {
    super(params, resolver);
    BuildTarget buildTarget = params.getBuildTarget();
    this.pathToOutputFile = BuildTargets.getGenPath(buildTarget, "%s.aar");
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.<Path>of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Clear out the old file, if it exists.
    commands.add(new RmStep(pathToOutputFile,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false));

    // Make sure the directory for the output file exists.
    commands.add(new MkdirStep(pathToOutputFile.getParent()));

    // TODO(user) replace logic with creating zip file *.aar
    commands.add(new TouchStep(pathToOutputFile));

    return commands.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

}
