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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.OcamlSource;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class OcamlLibraryDescription
    implements Description<OcamlLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            OcamlLibraryDescription.AbstractOcamlLibraryDescriptionArg>,
        VersionPropagator<OcamlLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;

  public OcamlLibraryDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
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

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(context.getBuildRuleResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    OcamlToolchain ocamlToolchain =
        toolchainProvider.getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class);
    OcamlPlatform ocamlPlatform = ocamlToolchain.getDefaultOcamlPlatform();

    ImmutableList<OcamlSource> srcs = args.getSrcs();
    ImmutableList.Builder<Arg> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(
        OcamlDescriptionEnhancer.toStringWithMacrosArgs(
            buildTarget,
            context.getCellPathResolver(),
            context.getBuildRuleResolver(),
            args.getCompilerFlags()));
    if (ocamlPlatform.getWarningsFlags().isPresent() || args.getWarningsFlags().isPresent()) {
      flagsBuilder.addAll(
          StringArg.from(
              "-w",
              ocamlPlatform.getWarningsFlags().orElse("") + args.getWarningsFlags().orElse("")));
    }
    ImmutableList<Arg> flags = flagsBuilder.build();

    BuildTarget compileBuildTarget = OcamlRuleBuilder.createStaticLibraryBuildTarget(buildTarget);

    if (OcamlRuleBuilder.shouldUseFineGrainedRules(context.getBuildRuleResolver(), srcs)) {
      OcamlGeneratedBuildRules result =
          OcamlRuleBuilder.createFineGrainedBuildRules(
              ocamlPlatform,
              compileBuildTarget,
              context.getProjectFilesystem(),
              params,
              context.getBuildRuleResolver(),
              srcs,
              /* isLibrary */ true,
              args.getBytecodeOnly(),
              flags,
              args.getOcamldepFlags(),
              !args.getBytecodeOnly() && args.getNativePlugin());
      return new OcamlStaticLibrary(
          buildTarget,
          compileBuildTarget,
          context.getProjectFilesystem(),
          params,
          args.getLinkerFlags(),
          result.getObjectFiles(),
          result.getOcamlContext(),
          result.getRules().get(0),
          result.getNativeCompileDeps(),
          result.getBytecodeCompileDeps(),
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .add(result.getBytecodeLink())
              .addAll(ruleFinder.filterBuildRuleInputs(result.getObjectFiles()))
              .build(),
          result
              .getRules()
              .stream()
              .map(BuildRule::getBuildTarget)
              .collect(ImmutableList.toImmutableList()));

    } else {
      OcamlBuild ocamlLibraryBuild =
          OcamlRuleBuilder.createBulkCompileRule(
              ocamlPlatform,
              compileBuildTarget,
              context.getProjectFilesystem(),
              params,
              context.getBuildRuleResolver(),
              srcs,
              /* isLibrary */ true,
              args.getBytecodeOnly(),
              flags,
              args.getOcamldepFlags());
      return new OcamlStaticLibrary(
          buildTarget,
          compileBuildTarget,
          context.getProjectFilesystem(),
          params,
          args.getLinkerFlags(),
          srcs.stream()
              .map(OcamlSource::getSource)
              .map(pathResolver::getAbsolutePath)
              .filter(OcamlUtil.ext(OcamlCompilables.OCAML_C))
              .map(ocamlLibraryBuild.getOcamlContext()::getCOutput)
              .map(input -> ExplicitBuildTargetSourcePath.of(compileBuildTarget, input))
              .collect(ImmutableList.toImmutableList()),
          ocamlLibraryBuild.getOcamlContext(),
          ocamlLibraryBuild,
          ImmutableSortedSet.of(ocamlLibraryBuild),
          ImmutableSortedSet.of(ocamlLibraryBuild),
          ImmutableSortedSet.of(ocamlLibraryBuild),
          ImmutableList.of(ocamlLibraryBuild.getBuildTarget()));
    }
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractOcamlLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        OcamlUtil.getParseTimeDeps(
            toolchainProvider
                .getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class)
                .getDefaultOcamlPlatform()));
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
