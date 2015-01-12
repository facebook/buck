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
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class Lex extends AbstractBuildRule {

  private final SourcePath lex;
  private final ImmutableList<String> flags;
  private final Path outputSource;
  private final Path outputHeader;
  private final SourcePath input;

  public Lex(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath lex,
      ImmutableList<String> flags,
      Path outputSource,
      Path outputHeader,
      SourcePath input) {

    super(params, resolver);

    this.lex = lex;
    this.flags = flags;
    this.outputSource = outputSource;
    this.outputHeader = outputHeader;
    this.input = input;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(input);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setInput("lex", lex)
        .set("flags", flags)
        .set("outputSource", outputSource.toString())
        .set("outputHeader", outputHeader.toString())
        // The input name gets baked into line markers.
        .set("input", input.toString());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // Cache the generated source and header.
    buildableContext.recordArtifact(outputHeader);
    buildableContext.recordArtifact(outputSource);

    return ImmutableList.of(
        new MkdirStep(outputSource.getParent()),
        new RmStep(outputSource, /* shouldForceDeletion */ true),
        new MkdirStep(outputHeader.getParent()),
        new RmStep(outputHeader, /* shouldForceDeletion */ true),
        new LexStep(
            getResolver().getPath(lex),
            flags,
            outputSource,
            outputHeader,
            getResolver().getPath(input)));
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

}
