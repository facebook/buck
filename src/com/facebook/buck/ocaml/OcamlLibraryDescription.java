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
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

public class OcamlLibraryDescription
    implements Description<OcamlLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            OcamlLibraryDescription.AbstractOcamlLibraryDescriptionArg>,
        VersionPropagator<OcamlLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final OcamlBuckConfig ocamlBuckConfig;

  public OcamlLibraryDescription(
      ToolchainProvider toolchainProvider, OcamlBuckConfig ocamlBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.ocamlBuckConfig = ocamlBuckConfig;
  }

  public OcamlBuckConfig getOcamlBuckConfig() {
    return ocamlBuckConfig;
  }

  @Override
  public Class<OcamlLibraryDescriptionArg> getConstructorArgType() {
    return OcamlLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      OcamlLibraryDescriptionArg args) {

    ImmutableList<OcamlSource> srcs = args.getSrcs();
    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(
        OcamlDescriptionEnhancer.toStringWithMacrosArgs(
            buildTarget,
            context.getCellPathResolver(),
            context.getBuildRuleResolver(),
            args.getCompilerFlags()));
    if (ocamlBuckConfig.getWarningsFlags().isPresent() || args.getWarningsFlags().isPresent()) {
      flags.addAll(
          StringArg.from(
              "-w",
              ocamlBuckConfig.getWarningsFlags().orElse("") + args.getWarningsFlags().orElse("")));
    }
    ImmutableList<String> linkerflags = args.getLinkerFlags();

    boolean bytecodeOnly = args.getBytecodeOnly();
    boolean nativePlugin = !bytecodeOnly && args.getNativePlugin();

    return OcamlRuleBuilder.createBuildRule(
        toolchainProvider,
        ocamlBuckConfig,
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getBuildRuleResolver(),
        srcs,
        /*isLibrary*/ true,
        bytecodeOnly,
        flags.build(),
        linkerflags,
        args.getOcamldepFlags(),
        nativePlugin);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractOcamlLibraryDescriptionArg constructorArg,
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
  interface AbstractOcamlLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    ImmutableList<OcamlSource> getSrcs();

    ImmutableList<StringWithMacros> getCompilerFlags();

    ImmutableList<String> getOcamldepFlags();

    ImmutableList<String> getLinkerFlags();

    Optional<String> getWarningsFlags();

    @Value.Default
    default boolean getBytecodeOnly() {
      return false;
    }

    @Value.Default
    default boolean getNativePlugin() {
      return false;
    }
  }
}
