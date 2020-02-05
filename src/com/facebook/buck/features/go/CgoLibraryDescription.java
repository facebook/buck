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

package com.facebook.buck.features.go;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.features.go.GoListStep.ListType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import org.immutables.value.Value;

public class CgoLibraryDescription
    implements DescriptionWithTargetGraph<CgoLibraryDescriptionArg>,
        MetadataProvidingDescription<CgoLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<CgoLibraryDescriptionArg>,
        VersionPropagator<CgoLibraryDescriptionArg>,
        Flavored {

  private final GoBuckConfig goBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final ToolchainProvider toolchainProvider;

  public CgoLibraryDescription(
      GoBuckConfig goBuckConfig, CxxBuckConfig cxxBuckConfig, ToolchainProvider toolchainProvider) {
    this.goBuckConfig = goBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<CgoLibraryDescriptionArg> getConstructorArgType() {
    return CgoLibraryDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return getGoToolchain(toolchainTargetConfiguration)
        .getPlatformFlavorDomain()
        .containsAnyOf(flavors);
  }

  private GoToolchain getGoToolchain(TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        GoToolchain.DEFAULT_NAME, toolchainTargetConfiguration, GoToolchain.class);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CgoLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    Optional<GoPlatform> platform =
        getGoToolchain(buildTarget.getTargetConfiguration())
            .getPlatformFlavorDomain()
            .getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)) {
      Preconditions.checkState(platform.isPresent());

      SourcePath output = graphBuilder.requireRule(buildTarget).getSourcePathToOutput();
      return Optional.of(
          metadataClass.cast(
              GoLinkable.of(
                  ImmutableMap.of(
                      args.getPackageName()
                          .map(Paths::get)
                          .orElse(goBuckConfig.getDefaultPackageName(buildTarget)),
                      output),
                  args.getExportedDeps())));
    } else if (buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      ImmutableList<BuildTarget> nonCxxDeps =
          args.getDeps().stream()
              .filter(target -> !(graphBuilder.requireRule(target) instanceof NativeLinkableGroup))
              .collect(ImmutableList.toImmutableList());

      return Optional.of(
          metadataClass.cast(
              GoDescriptors.requireTransitiveGoLinkables(
                  buildTarget,
                  graphBuilder,
                  platform.get(),
                  Iterables.concat(nonCxxDeps, args.getExportedDeps()),
                  /* includeSelf */ true)));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CgoLibraryDescriptionArg args) {

    GoToolchain goToolchain = getGoToolchain(buildTarget.getTargetConfiguration());
    Optional<GoPlatform> platform = goToolchain.getPlatformFlavorDomain().getValue(buildTarget);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (platform.isPresent()) {
      ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

      ImmutableList<BuildTarget> cxxDeps =
          params.getDeclaredDeps().get().stream()
              .filter(rule -> rule instanceof NativeLinkableGroup)
              .map(BuildRule::getBuildTarget)
              .collect(ImmutableList.toImmutableList());

      BuildTarget cgoLibTarget = buildTarget.withAppendedFlavors(InternalFlavor.of("cgo"));
      CGoLibrary lib =
          (CGoLibrary)
              CGoLibrary.create(
                  params,
                  cgoLibTarget,
                  projectFilesystem,
                  graphBuilder,
                  context.getCellPathResolver(),
                  cxxBuckConfig,
                  platform.get(),
                  args,
                  cxxDeps,
                  platform.get().getCGo());

      ImmutableList<BuildTarget> nonCxxDeps =
          params.getDeclaredDeps().get().stream()
              .filter(rule -> !(rule instanceof NativeLinkableGroup))
              .map(BuildRule::getBuildTarget)
              .collect(ImmutableList.toImmutableList());

      return GoDescriptors.createGoCompileRule(
          buildTarget,
          projectFilesystem,
          params,
          context.getActionGraphBuilder(),
          goBuckConfig,
          args.getPackageName()
              .map(Paths::get)
              .orElse(goBuckConfig.getDefaultPackageName(buildTarget)),
          new ImmutableSet.Builder<SourcePath>()
              .addAll(lib.getGeneratedGoSource())
              .addAll(args.getGoSrcs())
              .build(),
          args.getGoCompilerFlags(),
          args.getGoAssemblerFlags(),
          platform.get(),
          Iterables.concat(nonCxxDeps, args.getExportedDeps()),
          ImmutableList.of(cgoLibTarget),
          Arrays.asList(ListType.GoFiles, ListType.CgoFiles));
    }

    return new NoopBuildRuleWithDeclaredAndExtraDeps(buildTarget, projectFilesystem, params);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      CgoLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add the C/C++ platform deps.
    GoToolchain toolchain = getGoToolchain(buildTarget.getTargetConfiguration());
    toolchain
        .getPlatformFlavorDomain()
        .getValue(buildTarget)
        .ifPresent(
            platform ->
                targetGraphOnlyDepsBuilder.addAll(
                    CxxPlatforms.getParseTimeDeps(
                        buildTarget.getTargetConfiguration(), platform.getCxxPlatform())));
  }

  @RuleArg
  interface AbstractCgoLibraryDescriptionArg extends CxxBinaryDescription.CommonArg {
    ImmutableList<String> getCgoCompilerFlags();

    ImmutableList<String> getGoCompilerFlags();

    ImmutableList<String> getGoAssemblerFlags();

    Optional<String> getPackageName();

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getGoSrcs();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    @Override
    @Value.Default
    default ImmutableList<StringWithMacros> getCompilerFlags() {
      // used for compilers other than gcc (due to __gcc_struct__)
      return wrapFlags(ImmutableList.of("-Wno-unknown-attributes"));
    }

    @Override
    default CgoLibraryDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (CgoLibraryDescriptionArg) this;
      }
      return CgoLibraryDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }

  private static ImmutableList<StringWithMacros> wrapFlags(ImmutableList<String> flags) {
    return flags.stream()
        .map(StringWithMacros::ofConstantString)
        .collect(ImmutableList.toImmutableList());
  }
}
