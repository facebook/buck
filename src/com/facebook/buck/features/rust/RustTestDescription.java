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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.tool.BinaryWrapperRule;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.features.rust.RustBinaryDescription.Type;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map.Entry;
import java.util.Optional;

public class RustTestDescription
    implements DescriptionWithTargetGraph<RustTestDescriptionArg>,
        ImplicitDepsInferringDescription<RustTestDescription.AbstractRustTestDescriptionArg>,
        Flavored,
        VersionRoot<RustTestDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final RustBuckConfig rustBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public RustTestDescription(
      ToolchainProvider toolchainProvider,
      RustBuckConfig rustBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.rustBuckConfig = rustBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public Class<RustTestDescriptionArg> getConstructorArgType() {
    return RustTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      RustTestDescriptionArg args) {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    BuildTarget exeTarget = buildTarget.withAppendedFlavors(InternalFlavor.of("unittest"));
    CxxDeps allDeps =
        CxxDeps.builder()
            .addDeps(args.getDeps())
            .addDeps(args.getNamedDeps().values())
            .addPlatformDeps(args.getPlatformDeps())
            .build();

    RustBinaryDescription.Type type =
        RustBinaryDescription.BINARY_TYPE
            .getFlavorAndValue(buildTarget)
            .map(Entry::getValue)
            .orElse(Type.STATIC);

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    RustPlatform rustPlatform =
        RustCompileUtils.getRustPlatform(
                getRustToolchain(buildTarget.getTargetConfiguration()), buildTarget, args)
            .resolve(context.getActionGraphBuilder(), buildTarget.getTargetConfiguration());

    ImmutableList.Builder<StringArg> testFlags = new ImmutableList.Builder<>();
    testFlags.addAll(rustPlatform.getRustTestFlags());
    if (args.isFramework()) {
      testFlags.add(StringArg.of("--test"));
    }

    Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> flagsAndEnv =
        RustCompileUtils.getRustcFlagsAndEnv(
            context, buildTarget, rustPlatform, testFlags.build(), args);

    ImmutableList<Arg> linkerFlags =
        RustCompileUtils.getLinkerFlags(context, buildTarget, rustPlatform, args);

    BinaryWrapperRule testExeBuild =
        (BinaryWrapperRule)
            graphBuilder.computeIfAbsent(
                exeTarget,
                target ->
                    RustCompileUtils.createBinaryBuildRule(
                        target,
                        projectFilesystem,
                        params,
                        graphBuilder,
                        rustBuckConfig,
                        downwardApiConfig,
                        rustPlatform,
                        args.getCrate(),
                        args.getEdition(),
                        args.getFeatures(),
                        flagsAndEnv.getSecond(), // rustc environment
                        flagsAndEnv.getFirst(), // rustc flags
                        linkerFlags,
                        RustCompileUtils.getLinkStyle(buildTarget, args.getLinkStyle()),
                        args.isRpath(),
                        args.getSrcs(),
                        args.getMappedSrcs(),
                        args.getCrateRoot(),
                        ImmutableSet.of("lib.rs", "main.rs"),
                        type.getCrateType(),
                        allDeps.get(graphBuilder, rustPlatform.getCxxPlatform()),
                        args.getNamedDeps()));

    Tool testExe = testExeBuild.getExecutableCommand(OutputLabel.defaultLabel());

    BuildRuleParams testParams =
        params.copyAppendingExtraDeps(BuildableSupport.getDepsCollection(testExe, graphBuilder));

    return new RustTest(
        buildTarget,
        projectFilesystem,
        testParams,
        flagsAndEnv.getSecond(), // environment
        testExeBuild,
        args.getLabels(),
        args.getContacts(),
        downwardApiConfig.isEnabledForTests());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractRustTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        RustCompileUtils.getPlatformParseTimeDeps(
            getRustToolchain(buildTarget.getTargetConfiguration()), buildTarget, constructorArg));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            getRustToolchain(toolchainTargetConfiguration).getRustPlatforms(),
            RustBinaryDescription.BINARY_TYPE));
  }

  private RustToolchain getRustToolchain(TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        RustToolchain.DEFAULT_NAME, toolchainTargetConfiguration, RustToolchain.class);
  }

  @RuleArg
  interface AbstractRustTestDescriptionArg extends RustLinkableArgs {}
}
