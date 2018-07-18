/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasDefaultPlatform;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.tool.BinaryWrapperRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.features.rust.RustBinaryDescription.Type;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class RustTestDescription
    implements DescriptionWithTargetGraph<RustTestDescriptionArg>,
        ImplicitDepsInferringDescription<RustTestDescription.AbstractRustTestDescriptionArg>,
        Flavored,
        VersionRoot<RustTestDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final RustBuckConfig rustBuckConfig;

  public RustTestDescription(ToolchainProvider toolchainProvider, RustBuckConfig rustBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.rustBuckConfig = rustBuckConfig;
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
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();

    RustBinaryDescription.Type type =
        RustBinaryDescription.BINARY_TYPE
            .getFlavorAndValue(buildTarget)
            .map(Entry::getValue)
            .orElse(Type.STATIC);

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    RustPlatform rustPlatform =
        RustCompileUtils.getRustPlatform(getRustToolchain(), buildTarget, args);

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
                        rustPlatform,
                        args.getCrate(),
                        args.getFeatures(),
                        Stream.of(
                                args.isFramework() ? Stream.of("--test") : Stream.<String>empty(),
                                rustPlatform.getRustTestFlags().stream(),
                                args.getRustcFlags().stream())
                            .flatMap(x -> x)
                            .iterator(),
                        args.getLinkerFlags().iterator(),
                        RustCompileUtils.getLinkStyle(buildTarget, args.getLinkStyle()),
                        args.isRpath(),
                        args.getSrcs(),
                        args.getCrateRoot(),
                        ImmutableSet.of("lib.rs", "main.rs"),
                        type.getCrateType(),
                        allDeps.get(graphBuilder, rustPlatform.getCxxPlatform())));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    Tool testExe = testExeBuild.getExecutableCommand();

    BuildRuleParams testParams =
        params.copyAppendingExtraDeps(BuildableSupport.getDepsCollection(testExe, ruleFinder));

    return new RustTest(
        buildTarget,
        projectFilesystem,
        testParams,
        testExeBuild,
        args.getLabels(),
        args.getContacts());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractRustTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        RustCompileUtils.getPlatformParseTimeDeps(getRustToolchain(), buildTarget, constructorArg));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(
        ImmutableSet.of(getRustToolchain().getRustPlatforms(), RustBinaryDescription.BINARY_TYPE));
  }

  private RustToolchain getRustToolchain() {
    return toolchainProvider.getByName(RustToolchain.DEFAULT_NAME, RustToolchain.class);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractRustTestDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs, HasDefaultPlatform {
    ImmutableSet<String> getContacts();

    @Value.NaturalOrder
    ImmutableSortedSet<String> getFeatures();

    ImmutableList<String> getRustcFlags();

    ImmutableList<String> getLinkerFlags();

    Optional<Linker.LinkableDepType> getLinkStyle();

    @Value.Default
    default boolean isRpath() {
      return true;
    }

    @Value.Default
    default boolean isFramework() {
      return true;
    }

    Optional<String> getCrate();

    Optional<SourcePath> getCrateRoot();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
