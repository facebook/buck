/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class OcamlBinaryDescription
    implements DescriptionWithTargetGraph<OcamlBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<OcamlBinaryDescription.AbstractOcamlBinaryDescriptionArg>,
        VersionRoot<OcamlBinaryDescriptionArg> {

  private final DownwardApiConfig downwardApiConfig;
  private final ToolchainProvider toolchainProvider;

  public OcamlBinaryDescription(
      DownwardApiConfig downwardApiConfig, ToolchainProvider toolchainProvider) {
    this.downwardApiConfig = downwardApiConfig;
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<OcamlBinaryDescriptionArg> getConstructorArgType() {
    return OcamlBinaryDescriptionArg.class;
  }

  private UnresolvedOcamlPlatform getPlatform(
      Optional<Flavor> platformFlavor, TargetConfiguration toolchainTargetConfiguration) {
    OcamlToolchain ocamlToolchain =
        toolchainProvider.getByName(
            OcamlToolchain.DEFAULT_NAME, toolchainTargetConfiguration, OcamlToolchain.class);
    FlavorDomain<UnresolvedOcamlPlatform> ocamlPlatforms = ocamlToolchain.getOcamlPlatforms();
    return platformFlavor
        .map(ocamlPlatforms::getValue)
        .orElse(ocamlToolchain.getDefaultOcamlPlatform());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      OcamlBinaryDescriptionArg args) {

    OcamlPlatform ocamlPlatform =
        getPlatform(args.getPlatform(), buildTarget.getTargetConfiguration())
            .resolve(context.getActionGraphBuilder(), buildTarget.getTargetConfiguration());

    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();

    ImmutableList<SourcePath> srcs =
        args.getSrcs().isPresent() ? args.getSrcs().get().getPaths() : ImmutableList.of();

    ImmutableList<Arg> flags =
        OcamlRuleBuilder.getFlags(
            buildTarget,
            context.getCellPathResolver(),
            context.getActionGraphBuilder(),
            ocamlPlatform,
            args.getCompilerFlags(),
            args.getWarningsFlags());

    BuildTarget compileBuildTarget = OcamlRuleBuilder.createOcamlLinkTarget(buildTarget);

    ImmutableList<BuildRule> rules;
    boolean withDownwardApi = downwardApiConfig.isEnabledForOCaml();
    if (OcamlRuleBuilder.shouldUseFineGrainedRules(context.getActionGraphBuilder(), srcs)) {
      OcamlGeneratedBuildRules result =
          OcamlRuleBuilder.createFineGrainedBuildRules(
              buildTarget,
              ocamlPlatform,
              compileBuildTarget,
              context.getProjectFilesystem(),
              params,
              context.getActionGraphBuilder(),
              allDeps.get(context.getActionGraphBuilder(), ocamlPlatform.getCxxPlatform()),
              srcs,
              /* isLibrary */ false,
              args.getBytecodeOnly().orElse(false),
              flags,
              args.getOcamldepFlags(),
              /* buildNativePlugin */ false,
              withDownwardApi);
      rules = result.getRules();
    } else {

      OcamlBuild ocamlLibraryBuild =
          OcamlRuleBuilder.createBulkCompileRule(
              buildTarget,
              ocamlPlatform,
              compileBuildTarget,
              context.getProjectFilesystem(),
              params,
              context.getActionGraphBuilder(),
              allDeps.get(context.getActionGraphBuilder(), ocamlPlatform.getCxxPlatform()),
              srcs,
              /* isLibrary */ false,
              args.getBytecodeOnly().orElse(false),
              flags,
              args.getOcamldepFlags(),
              withDownwardApi);
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
      CellNameResolver cellRoots,
      AbstractOcamlBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        getPlatform(constructorArg.getPlatform(), buildTarget.getTargetConfiguration())
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));
  }

  @RuleArg
  interface AbstractOcamlBinaryDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    Optional<SourceSet> getSrcs();

    ImmutableList<StringWithMacros> getCompilerFlags();

    ImmutableList<String> getLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

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
