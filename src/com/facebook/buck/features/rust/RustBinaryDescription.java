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
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map.Entry;
import java.util.Optional;

public class RustBinaryDescription
    implements DescriptionWithTargetGraph<RustBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<RustBinaryDescription.AbstractRustBinaryDescriptionArg>,
        Flavored,
        VersionRoot<RustBinaryDescriptionArg> {

  public static final FlavorDomain<Type> BINARY_TYPE =
      FlavorDomain.from("Rust Binary Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final RustBuckConfig rustBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public RustBinaryDescription(
      ToolchainProvider toolchainProvider,
      RustBuckConfig rustBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.rustBuckConfig = rustBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
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
        CxxDeps.builder()
            .addDeps(args.getDeps())
            .addDeps(args.getNamedDeps().values())
            .addPlatformDeps(args.getPlatformDeps())
            .build();

    RustBinaryDescription.Type type =
        BINARY_TYPE.getFlavorAndValue(buildTarget).map(Entry::getValue).orElse(Type.STATIC);

    RustToolchain rustToolchain = getRustToolchain(buildTarget.getTargetConfiguration());
    RustPlatform rustPlatform =
        RustCompileUtils.getRustPlatform(rustToolchain, buildTarget, args)
            .resolve(context.getActionGraphBuilder(), buildTarget.getTargetConfiguration());

    Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> flagsAndEnv =
        RustCompileUtils.getRustcFlagsAndEnv(
            context, buildTarget, rustPlatform, rustPlatform.getRustBinaryFlags(), args);

    ImmutableList<Arg> linkerFlags =
        RustCompileUtils.getLinkerFlags(context, buildTarget, rustPlatform, args);

    return RustCompileUtils.createBinaryBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getActionGraphBuilder(),
        rustBuckConfig,
        downwardApiConfig,
        rustPlatform,
        args.getCrate(),
        args.getEdition(),
        flagsAndEnv.getSecond(), // rustc environment
        flagsAndEnv.getFirst(), // rustc flags
        linkerFlags,
        RustCompileUtils.getLinkStyle(buildTarget, args.getLinkStyle(), rustPlatform),
        args.isRpath(),
        args.getSrcs(),
        args.getMappedSrcs(),
        args.getCrateRoot(),
        ImmutableSet.of("main.rs"),
        type.getCrateType(),
        allDeps.get(context.getActionGraphBuilder(), rustPlatform.getCxxPlatform()),
        args.getNamedDeps());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractRustBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        RustCompileUtils.getPlatformParseTimeDeps(
            getRustToolchain(buildTarget.getTargetConfiguration()), buildTarget, constructorArg));
  }

  protected enum Type implements FlavorConvertible {
    CHECK(RustDescriptionEnhancer.RFCHECK, Linker.LinkableDepType.STATIC_PIC, CrateType.CHECKBIN),
    DOC(RustDescriptionEnhancer.RFDOC, Linker.LinkableDepType.STATIC_PIC, CrateType.DOCBIN),
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

    public boolean isDoc() {
      return flavor == RustDescriptionEnhancer.RFDOC;
    }

    public boolean isSaveAnalysis() {
      return flavor == RustDescriptionEnhancer.RFSAVEANALYSIS;
    }
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            getRustToolchain(toolchainTargetConfiguration).getRustPlatforms(), BINARY_TYPE));
  }

  private RustToolchain getRustToolchain(TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        RustToolchain.DEFAULT_NAME, toolchainTargetConfiguration, RustToolchain.class);
  }

  @RuleArg
  interface AbstractRustBinaryDescriptionArg extends RustLinkableArgs, HasTests {}
}
