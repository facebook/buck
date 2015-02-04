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

public class Yacc extends AbstractBuildRule {

  private final SourcePath yacc;
  private final ImmutableList<String> flags;
  private final Path outputPrefix;
  private final SourcePath input;

  public Yacc(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath yacc,
      ImmutableList<String> flags,
      Path outputPrefix,
      SourcePath input) {
    super(params, resolver);
    this.yacc = yacc;
    this.flags = flags;
    this.outputPrefix = outputPrefix;
    this.input = input;
  }

  public static Path getHeaderOutputPath(Path outputPrefix) {
    return YaccStep.getHeaderOutputPath(outputPrefix);
  }

  public static Path getSourceOutputPath(Path outputPrefix) {
    return YaccStep.getSourceOutputPath(outputPrefix);
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(input);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("yacc", yacc)
        .setReflectively("flags", flags)
        .setReflectively("outputPrefix", outputPrefix.toString())
        // The input name gets baked into line markers.
        .setReflectively("input", input.toString());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // Cache the output source and header.
    buildableContext.recordArtifact(YaccStep.getHeaderOutputPath(outputPrefix));
    buildableContext.recordArtifact(YaccStep.getSourceOutputPath(outputPrefix));

    return ImmutableList.of(
        new MkdirStep(outputPrefix.getParent()),
        new RmStep(getHeaderOutputPath(outputPrefix), /* shouldForceDeletion */ true),
        new RmStep(getSourceOutputPath(outputPrefix), /* shouldForceDeletion */ true),
        new YaccStep(
            getResolver().getPath(yacc),
            flags,
            outputPrefix,
            getResolver().getPath(input)));
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

}
