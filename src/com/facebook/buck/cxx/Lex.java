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
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class Lex extends AbstractBuildRule {

  @AddToRuleKey
  private final Tool lex;
  @AddToRuleKey
  private final ImmutableList<String> flags;
  @AddToRuleKey(stringify = true)
  private final Path outputSource;
  @AddToRuleKey(stringify = true)
  private final Path outputHeader;
  @AddToRuleKey
  private final SourcePath input;

  public Lex(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool lex,
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
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // Cache the generated source and header.
    buildableContext.recordArtifact(outputHeader);
    buildableContext.recordArtifact(outputSource);

    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), outputSource.getParent()),
        new RmStep(getProjectFilesystem(), outputSource, /* shouldForceDeletion */ true),
        new MkdirStep(getProjectFilesystem(), outputHeader.getParent()),
        new RmStep(getProjectFilesystem(), outputHeader, /* shouldForceDeletion */ true),
        new LexStep(
            getProjectFilesystem().getRootPath(),
            lex.getCommandPrefix(getResolver()),
            flags,
            outputSource,
            outputHeader,
            getResolver().getPath(input)));
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return null;
  }

}
