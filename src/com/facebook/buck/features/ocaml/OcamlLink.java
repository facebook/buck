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

package com.facebook.buck.features.ocaml;

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
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

public class OcamlLink extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final ImmutableList<SourcePath> inputs;
  @AddToRuleKey private final ImmutableMap<String, String> cxxCompilerEnvironment;
  @AddToRuleKey private final ImmutableList<String> cxxCompiler;
  @AddToRuleKey private final Tool ocamlCompiler;
  @AddToRuleKey private final ImmutableList<Arg> flags;
  @AddToRuleKey private final Optional<String> stdlib;

  @AddToRuleKey(stringify = true)
  private final Path outputRelativePath;

  @AddToRuleKey(stringify = true)
  private final Path outputNativePluginPath;

  @AddToRuleKey private final ImmutableList<Arg> depInput;
  @AddToRuleKey private final ImmutableList<Arg> cDepInput;
  @AddToRuleKey private final boolean isLibrary;
  @AddToRuleKey private final boolean isBytecode;
  @AddToRuleKey private final boolean buildNativePlugin;

  public OcamlLink(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableList<SourcePath> inputs,
      ImmutableMap<String, String> cxxCompilerEnvironment,
      ImmutableList<String> cxxCompiler,
      Tool ocamlCompiler,
      ImmutableList<Arg> flags,
      Optional<String> stdlib,
      Path outputRelativePath,
      Path outputNativePluginPath,
      ImmutableList<Arg> depInput,
      ImmutableList<Arg> cDepInput,
      boolean isLibrary,
      boolean isBytecode,
      boolean buildNativePlugin) {
    super(buildTarget, projectFilesystem, params);

    this.inputs = inputs;
    this.cxxCompilerEnvironment = cxxCompilerEnvironment;
    this.cxxCompiler = cxxCompiler;
    this.ocamlCompiler = ocamlCompiler;
    this.flags = flags;
    this.stdlib = stdlib;
    this.outputRelativePath = outputRelativePath;
    this.outputNativePluginPath = outputNativePluginPath;
    this.depInput = depInput;
    this.cDepInput = cDepInput;
    this.isLibrary = isLibrary;
    this.isBytecode = isBytecode;
    this.buildNativePlugin = buildNativePlugin;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    for (Path artifact : getAllOutputs()) {
      buildableContext.recordArtifact(artifact);
    }

    ImmutableList.Builder<Step> steps =
        ImmutableList.<Step>builder()
            .add(
                MkdirStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        context.getBuildCellRootPath(),
                        getProjectFilesystem(),
                        outputRelativePath.getParent())))
            .add(
                OcamlLinkStep.create(
                    getBuildTarget(),
                    getProjectFilesystem().getRootPath(),
                    cxxCompilerEnvironment,
                    cxxCompiler,
                    ocamlCompiler.getCommandPrefix(context.getSourcePathResolver()),
                    flags,
                    stdlib,
                    getProjectFilesystem().resolve(outputRelativePath),
                    depInput,
                    cDepInput,
                    inputs
                        .stream()
                        .map(context.getSourcePathResolver()::getAbsolutePath)
                        .collect(ImmutableList.toImmutableList()),
                    isLibrary,
                    isBytecode,
                    context.getSourcePathResolver()));
    if (isLibrary && buildNativePlugin) {
      ImmutableList.Builder<String> ocamlInputBuilder = ImmutableList.builder();

      String linkExt = OcamlCompilables.OCAML_CMXS;

      for (String linkInput : Arg.stringify(depInput, context.getSourcePathResolver())) {
        if (linkInput.endsWith(linkExt)) {
          ocamlInputBuilder.add(linkInput);
        }
      }

      ImmutableList<String> ocamlInput = ocamlInputBuilder.build();
      steps.add(
          new OcamlNativePluginStep(
              getBuildTarget(),
              getProjectFilesystem().getRootPath(),
              cxxCompilerEnvironment,
              cxxCompiler,
              ocamlCompiler.getCommandPrefix(context.getSourcePathResolver()),
              Arg.stringify(flags, context.getSourcePathResolver()),
              stdlib,
              getProjectFilesystem().resolve(outputNativePluginPath),
              cDepInput,
              inputs
                  .stream()
                  .map(context.getSourcePathResolver()::getAbsolutePath)
                  .collect(ImmutableList.toImmutableList()),
              ocamlInput));
    }
    return steps.build();
  }

  private ImmutableSet<Path> getAllOutputs() {
    if (isLibrary && !isBytecode) {
      return OcamlUtil.getExtensionVariants(
          outputRelativePath, OcamlCompilables.OCAML_A, OcamlCompilables.OCAML_CMXA);
    } else {
      return ImmutableSet.of(outputRelativePath);
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputRelativePath);
  }

  @Override
  public boolean isCacheable() {
    // TODO(10456582): when some libraries are fetched from cache and others are built locally
    // The digest of the implementations may not match up, and ocaml throws a fit.
    // In lieu of tracking that down, forcing libraries to not cache will ensure that all libraries
    // will rely on identical object files.
    return false;
  }
}
