/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class RustBinaryDescription
    implements DescriptionWithTargetGraph<RustBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<RustBinaryDescription.AbstractRustBinaryDescriptionArg>,
        Flavored,
        VersionRoot<RustBinaryDescriptionArg> {

  public static final FlavorDomain<Type> BINARY_TYPE =
      FlavorDomain.from("Rust Binary Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final RustBuckConfig rustBuckConfig;

  public RustBinaryDescription(ToolchainProvider toolchainProvider, RustBuckConfig rustBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.rustBuckConfig = rustBuckConfig;
  }

  @Override
  public Class<RustBinaryDescriptionArg> getConstructorArgType() {
    return RustBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      RustBinaryDescriptionArg args) {
    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();
    Linker.LinkableDepType linkStyle =
        RustCompileUtils.getLinkStyle(buildTarget, args.getLinkStyle());

    RustBinaryDescription.Type type =
        BINARY_TYPE.getFlavorAndValue(buildTarget).map(Entry::getValue).orElse(Type.STATIC);

    RustToolchain rustToolchain = getRustToolchain();
    RustPlatform rustPlatform = RustCompileUtils.getRustPlatform(rustToolchain, buildTarget, args);

    return RustCompileUtils.createBinaryBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getActionGraphBuilder(),
        rustBuckConfig,
        rustPlatform,
        args.getCrate(),
        args.getFeatures(),
        Stream.of(rustPlatform.getRustBinaryFlags().stream(), args.getRustcFlags().stream())
            .flatMap(x -> x)
            .iterator(),
        args.getLinkerFlags().iterator(),
        linkStyle,
        args.isRpath(),
        args.getSrcs(),
        args.getCrateRoot(),
        ImmutableSet.of("main.rs"),
        type.getCrateType(),
        allDeps.get(context.getActionGraphBuilder(), rustPlatform.getCxxPlatform()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractRustBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        RustCompileUtils.getPlatformParseTimeDeps(getRustToolchain(), buildTarget, constructorArg));
  }

  protected enum Type implements FlavorConvertible {
    CHECK(RustDescriptionEnhancer.RFCHECK, Linker.LinkableDepType.STATIC_PIC, CrateType.CHECKBIN),
    SAVEANALYSIS(
        RustDescriptionEnhancer.RFSAVEANALYSIS,
        Linker.LinkableDepType.STATIC_PIC,
        CrateType.SAVEANALYSISBIN),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR, Linker.LinkableDepType.SHARED, CrateType.BIN),
    STATIC_PIC(
        CxxDescriptionEnhancer.STATIC_PIC_FLAVOR, Linker.LinkableDepType.STATIC_PIC, CrateType.BIN),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR, Linker.LinkableDepType.STATIC, CrateType.BIN),
    ;

    private final Flavor flavor;
    private final Linker.LinkableDepType linkStyle;
    private final CrateType crateType;

    Type(Flavor flavor, Linker.LinkableDepType linkStyle, CrateType crateType) {
      this.flavor = flavor;
      this.linkStyle = linkStyle;
      this.crateType = crateType;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

    public Linker.LinkableDepType getLinkStyle() {
      return linkStyle;
    }

    public CrateType getCrateType() {
      return crateType;
    }

    public boolean isCheck() {
      return flavor == RustDescriptionEnhancer.RFCHECK || this.isSaveAnalysis();
    }

    public boolean isSaveAnalysis() {
      return flavor == RustDescriptionEnhancer.RFSAVEANALYSIS;
    }
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(ImmutableSet.of(getRustToolchain().getRustPlatforms(), BINARY_TYPE));
  }

  private RustToolchain getRustToolchain() {
    return toolchainProvider.getByName(RustToolchain.DEFAULT_NAME, RustToolchain.class);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractRustBinaryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs, HasTests, HasDefaultPlatform {
    @Value.NaturalOrder
    ImmutableSortedSet<String> getFeatures();

    ImmutableList<String> getRustcFlags();

    ImmutableList<String> getLinkerFlags();

    Optional<Linker.LinkableDepType> getLinkStyle();

    Optional<String> getCrate();

    Optional<SourcePath> getCrateRoot();

    @Value.Default
    default boolean isRpath() {
      return true;
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
