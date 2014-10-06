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

package com.facebook.buck.ocaml;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A build rule which preprocesses, compiles, and assembles an OCaml source.
 */
public class OCamlBuild extends AbstractBuildRule {

  private final OCamlBuildContext ocamlContext;

  public OCamlBuild(
      BuildRuleParams params,
      OCamlBuildContext ocamlContext) {
    super(params);
    this.ocamlContext = Preconditions.checkNotNull(ocamlContext);
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.toPaths(ocamlContext.getInput());
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return ocamlContext.appendDetailsToRuleKey(builder);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(ocamlContext.getOutput());
    return ImmutableList.of(
        new MakeCleanDirectoryStep(ocamlContext.getOutput().getParent()),
        new OCamlBuildStep(ocamlContext));
  }

  @Override
  public Path getPathToOutputFile() {
    return ocamlContext.getOutput();
  }

}
