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

import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** A build rule which preprocesses, compiles, and assembles an OCaml source. */
public class OcamlBuild extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final OcamlBuildContext ocamlContext;
  @AddToRuleKey private final Compiler cCompiler;
  @AddToRuleKey private final Compiler cxxCompiler;
  @AddToRuleKey private final boolean bytecodeOnly;

  public OcamlBuild(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      OcamlBuildContext ocamlContext,
      Compiler cCompiler,
      Compiler cxxCompiler,
      boolean bytecodeOnly) {
    super(buildTarget, projectFilesystem, params);
    this.ocamlContext = ocamlContext;
    this.cCompiler = cCompiler;
    this.cxxCompiler = cxxCompiler;
    this.bytecodeOnly = bytecodeOnly;

    Preconditions.checkNotNull(ocamlContext.getInput());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path baseArtifactDir = ocamlContext.getNativeOutput().getParent();
    buildableContext.recordArtifact(baseArtifactDir);
    if (!bytecodeOnly) {
      buildableContext.recordArtifact(
          baseArtifactDir.resolve(OcamlBuildContext.OCAML_COMPILED_DIR));
    }
    buildableContext.recordArtifact(
        baseArtifactDir.resolve(OcamlBuildContext.OCAML_COMPILED_BYTECODE_DIR));
    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    ocamlContext.getNativeOutput().getParent())))
        .add(
            new OcamlBuildStep(
                getBuildTarget(),
                context,
                getProjectFilesystem(),
                ocamlContext,
                cCompiler.getEnvironment(context.getSourcePathResolver()),
                cCompiler.getCommandPrefix(context.getSourcePathResolver()),
                cxxCompiler.getEnvironment(context.getSourcePathResolver()),
                cxxCompiler.getCommandPrefix(context.getSourcePathResolver()),
                bytecodeOnly))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        bytecodeOnly ? ocamlContext.getBytecodeOutput() : ocamlContext.getNativeOutput());
  }

  @Override
  public boolean isCacheable() {
    // Intermediate OCaml rules are not cacheable because the compiler is not deterministic.
    return false;
  }
}
