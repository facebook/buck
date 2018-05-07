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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.OcamlSource;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class OcamlBinaryDescription
    implements Description<OcamlBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<OcamlBinaryDescription.AbstractOcamlBinaryDescriptionArg>,
        VersionRoot<OcamlBinaryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;

  public OcamlBinaryDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<OcamlBinaryDescriptionArg> getConstructorArgType() {
    return OcamlBinaryDescriptionArg.class;
  }

  private OcamlPlatform getPlatform(Optional<Flavor> platformFlavor) {
    OcamlToolchain ocamlToolchain =
        toolchainProvider.getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class);
    FlavorDomain<OcamlPlatform> ocamlPlatforms = ocamlToolchain.getOcamlPlatforms();
    return platformFlavor
        .map(ocamlPlatforms::getValue)
        .orElse(ocamlToolchain.getDefaultOcamlPlatform());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      OcamlBinaryDescriptionArg args) {

    OcamlPlatform ocamlPlatform = getPlatform(args.getPlatform());

    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();

    ImmutableList<OcamlSource> srcs = args.getSrcs();

    ImmutableList<Arg> flags =
        OcamlRuleBuilder.getFlags(
            buildTarget,
            context.getCellPathResolver(),
            context.getBuildRuleResolver(),
            ocamlPlatform,
            args.getCompilerFlags(),
            args.getWarningsFlags());

    BuildTarget compileBuildTarget = OcamlRuleBuilder.createOcamlLinkTarget(buildTarget);

    ImmutableList<BuildRule> rules;
    if (OcamlRuleBuilder.shouldUseFineGrainedRules(context.getBuildRuleResolver(), srcs)) {
      OcamlGeneratedBuildRules result =
          OcamlRuleBuilder.createFineGrainedBuildRules(
              buildTarget,
              ocamlPlatform,
              compileBuildTarget,
              context.getProjectFilesystem(),
              params,
              context.getBuildRuleResolver(),
              allDeps.get(context.getBuildRuleResolver(), ocamlPlatform.getCxxPlatform()),
              srcs,
              /* isLibrary */ false,
              args.getBytecodeOnly().orElse(false),
              flags,
              args.getOcamldepFlags(),
              /* buildNativePlugin */ false);
      rules = result.getRules();
    } else {

      OcamlBuild ocamlLibraryBuild =
          OcamlRuleBuilder.createBulkCompileRule(
              buildTarget,
              ocamlPlatform,
              compileBuildTarget,
              context.getProjectFilesystem(),
              params,
              context.getBuildRuleResolver(),
              allDeps.get(context.getBuildRuleResolver(), ocamlPlatform.getCxxPlatform()),
              srcs,
              /* isLibrary */ false,
              args.getBytecodeOnly().orElse(false),
              flags,
              args.getOcamldepFlags());
      rules = ImmutableList.of(ocamlLibraryBuild);
    }

    return new OcamlBinary(
        buildTarget,
        context.getProjectFilesystem(),
        params.withDeclaredDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(params.getDeclaredDeps().get())
                    .addAll(rules)
                    .build())),
        rules.get(0));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractOcamlBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        OcamlUtil.getParseTimeDeps(getPlatform(constructorArg.getPlatform())));
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

    Optional<Flavor> getPlatform();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
