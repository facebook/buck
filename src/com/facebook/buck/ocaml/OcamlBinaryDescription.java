/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.OcamlSource;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

public class OcamlBinaryDescription
    implements Description<OcamlBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<OcamlBinaryDescription.AbstractOcamlBinaryDescriptionArg>,
        VersionRoot<OcamlBinaryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final OcamlBuckConfig ocamlBuckConfig;

  public OcamlBinaryDescription(
      ToolchainProvider toolchainProvider, OcamlBuckConfig ocamlBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.ocamlBuckConfig = ocamlBuckConfig;
  }

  public OcamlBuckConfig getOcamlBuckConfig() {
    return ocamlBuckConfig;
  }

  @Override
  public Class<OcamlBinaryDescriptionArg> getConstructorArgType() {
    return OcamlBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      OcamlBinaryDescriptionArg args) {

    ImmutableList<OcamlSource> srcs = args.getSrcs();
    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(
        OcamlDescriptionEnhancer.toStringWithMacrosArgs(
            buildTarget,
            context.getCellPathResolver(),
            context.getBuildRuleResolver(),
            args.getCompilerFlags()));
    if (ocamlBuckConfig.getWarningsFlags().isPresent() || args.getWarningsFlags().isPresent()) {
      flags.addAll(StringArg.from("-w"));
      flags.addAll(
          StringArg.from(
              ocamlBuckConfig.getWarningsFlags().orElse("") + args.getWarningsFlags().orElse("")));
    }
    ImmutableList<String> linkerFlags = args.getLinkerFlags();

    return OcamlRuleBuilder.createBuildRule(
        toolchainProvider,
        ocamlBuckConfig,
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getBuildRuleResolver(),
        srcs,
        /*isLibrary*/ false,
        args.getBytecodeOnly().orElse(false),
        flags.build(),
        linkerFlags,
        args.getOcamldepFlags(),
        /*buildNativePlugin*/ false);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractOcamlBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(
            toolchainProvider
                .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
                .getDefaultCxxPlatform()));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractOcamlBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    ImmutableList<OcamlSource> getSrcs();

    ImmutableList<StringWithMacros> getCompilerFlags();

    ImmutableList<String> getLinkerFlags();

    ImmutableList<String> getOcamldepFlags();

    Optional<String> getWarningsFlags();

    Optional<Boolean> getBytecodeOnly();
  }
}
