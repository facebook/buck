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

package com.facebook.buck.file;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class WriteFile extends AbstractBuildRule {

  @AddToRuleKey
  private final String fileContents;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final boolean executable;

  public WriteFile(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      String fileContents,
      Path output,
      boolean executable) {
    super(buildRuleParams, resolver);

    this.fileContents = fileContents;
    this.output = output;
    this.executable = executable;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new WriteFileStep(fileContents, output, executable));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

}
