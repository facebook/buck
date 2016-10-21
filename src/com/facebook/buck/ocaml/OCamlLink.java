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
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Optional;

public class OCamlLink extends AbstractBuildRule {

  @AddToRuleKey
  private final ImmutableList<SourcePath> inputs;
  @AddToRuleKey
  private final ImmutableMap<String, String> cxxCompilerEnvironment;
  @AddToRuleKey
  private final ImmutableList<String> cxxCompiler;
  @AddToRuleKey
  private final Tool ocamlCompiler;
  @AddToRuleKey
  private final ImmutableList<String> flags;
  @AddToRuleKey
  private final Optional<String> stdlib;
  @AddToRuleKey(stringify = true)
  private final Path outputRelativePath;
  @AddToRuleKey
  private final ImmutableList<Arg> depInput;
  @AddToRuleKey
  private final ImmutableList<Arg> cDepInput;
  @AddToRuleKey
  private final boolean isLibrary;
  @AddToRuleKey
  private final boolean isBytecode;

  public OCamlLink(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<SourcePath> inputs,
      ImmutableMap<String, String> cxxCompilerEnvironment,
      ImmutableList<String> cxxCompiler,
      Tool ocamlCompiler,
      ImmutableList<String> flags,
      Optional<String> stdlib,
      Path outputRelativePath,
      ImmutableList<Arg> depInput,
      ImmutableList<Arg> cDepInput,
      boolean isLibrary,
      boolean isBytecode) {
    super(params, resolver);

    this.inputs = inputs;
    this.cxxCompilerEnvironment = cxxCompilerEnvironment;
    this.cxxCompiler = cxxCompiler;
    this.ocamlCompiler = ocamlCompiler;
    this.flags = flags;
    this.stdlib = stdlib;
    this.outputRelativePath = outputRelativePath;
    this.depInput = depInput;
    this.cDepInput = cDepInput;
    this.isLibrary = isLibrary;
    this.isBytecode = isBytecode;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    for (Path artifact : getAllOutputs()) {
      buildableContext.recordArtifact(artifact);
    }

    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), outputRelativePath.getParent()),
        new OCamlLinkStep(
            getProjectFilesystem().getRootPath(),
            cxxCompilerEnvironment,
            cxxCompiler,
            ocamlCompiler.getCommandPrefix(getResolver()),
            flags,
            stdlib,
            getProjectFilesystem().resolve(outputRelativePath),
            depInput,
            cDepInput,
            inputs.stream()
                .map(getResolver()::getAbsolutePath)
                .collect(MoreCollectors.toImmutableList()),
            isLibrary,
            isBytecode)
    );
  }

  private ImmutableSet<Path> getAllOutputs() {
    if (isLibrary && !isBytecode) {
      return OCamlUtil.getExtensionVariants(
          outputRelativePath,
          OCamlCompilables.OCAML_A,
          OCamlCompilables.OCAML_CMXA);
    } else {
      return ImmutableSet.of(outputRelativePath);
    }
  }

  @Override
  public Path getPathToOutput() {
    return outputRelativePath;
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
