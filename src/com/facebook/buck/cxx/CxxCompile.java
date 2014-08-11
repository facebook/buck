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
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A build rule which preprocesses, compiles, and assembles a C/C++ source.
 */
public class CxxCompile extends AbstractBuildRule {

  private final Path compiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final SourcePath input;
  private final ImmutableList<Path> includes;
  private final ImmutableList<Path> systemIncludes;

  public CxxCompile(
      BuildRuleParams params,
      Path compiler,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      ImmutableList<Path> includes,
      ImmutableList<Path> systemIncludes) {
    super(params);
    this.compiler = Preconditions.checkNotNull(compiler);
    this.flags = Preconditions.checkNotNull(flags);
    this.output = Preconditions.checkNotNull(output);
    this.input = Preconditions.checkNotNull(input);
    this.includes = Preconditions.checkNotNull(includes);
    this.systemIncludes = Preconditions.checkNotNull(systemIncludes);
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(input.resolve());
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setInput("compiler", compiler)
        .set("flags", flags);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new CxxCompileStep(compiler, flags, output, input.resolve(), includes, systemIncludes));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

}
