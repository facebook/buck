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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.MappedSymlinkTree;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class HaskellBinaryDescription
    implements DescriptionWithTargetGraph<HaskellBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<
            HaskellBinaryDescription.AbstractHaskellBinaryDescriptionArg>,
        Flavored,
        VersionRoot<HaskellBinaryDescriptionArg> {

  private static final FlavorDomain<Type> BINARY_TYPE =
      FlavorDomain.from("Haskell Binary Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;

  public HaskellBinaryDescription(
      ToolchainProvider toolchainProvider, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<HaskellBinaryDescriptionArg> getConstructorArgType() {
    return HaskellBinaryDescriptionArg.class;
  }

  private Linker.LinkableDepType getLinkStyle(
      HaskellBinaryDescriptionArg arg, Optional<Type> type) {
    if (type.isPresent()) {
      return type.get().getLinkStyle();
    }
    if (arg.getLinkStyle().isPresent()) {
      return arg.getLinkStyle().get();
    }
    return Linker.LinkableDepType.STATIC;
  }

  // Return the C/C++ platform to build against.
  private HaskellPlatform getPlatform(BuildTarget target, AbstractHaskellBinaryDescriptionArg arg) {
    HaskellPlatformsProvider haskellPlatformsProvider =
        getHaskellPlatformsProvider(target.getTargetConfiguration());
    FlavorDomain<HaskellPlatform> platforms = haskellPlatformsProvider.getHaskellPlatforms();

    Optional<HaskellPlatform> flavorPlatform = platforms.getValue(target);
    if (flavorPlatform.isPresent()) {
      return flavorPlatform.get();
    }

    if (arg.getPlatform().isPresent()) {
      return platforms.getValue(arg.getPlatform().get());
    }

    return haskellPlatformsProvider.getDefaultHaskellPlatform();
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HaskellBinaryDescriptionArg args) {

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    HaskellPlatform platform = getPlatform(buildTarget, args);

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    Optional<Type> type = BINARY_TYPE.getValue(buildTarget);
    // Handle #ghci flavor
    if (type.isPresent() && type.get() == Type.GHCI) {
      return HaskellDescriptionUtils.requireGhciRule(
          buildTarget,
          projectFilesystem,
          params,
          cellRoots,
          graphBuilder,
          platform,
          cxxBuckConfig,
          args.getDeps(),
          args.getPlatformDeps(),
          args.getSrcs(),
          args.getGhciPreloadDeps(),
          args.getGhciPlatformPreloadDeps(),
          args.getCompilerFlags(),
          Optional.empty(),
          Optional.empty(),
          ImmutableList.of(),
          args.isEnableProfiling());
    }

    Linker.LinkableDepType depType = getLinkStyle(args, type);

    // The target to use for the link rule.
    BuildTarget binaryTarget = buildTarget.withFlavors(InternalFlavor.of("binary"));

    // Maintain backwards compatibility to ease upgrade flows.
    if (platform.shouldUsedOldBinaryOutputLocation().orElse(true)) {
      binaryTarget = binaryTarget.withAppendedFlavors(platform.getFlavor());
    }

    ImmutableSet.Builder<BuildRule> depsBuilder = ImmutableSet.builder();

    depsBuilder.addAll(
        CxxDeps.builder()
            .addDeps(args.getDeps())
            .addPlatformDeps(args.getPlatformDeps())
            .build()
            .get(graphBuilder, platform.getCxxPlatform()));

    ImmutableList<BuildRule> depQueryDeps =
        args.getDepsQuery()
            .map(
                query ->
                    Objects.requireNonNull(query.getResolvedQuery()).stream()
                        .map(graphBuilder::getRule)
                        .filter(NativeLinkableGroup.class::isInstance))
            .orElse(Stream.of())
            .collect(ImmutableList.toImmutableList());
    depsBuilder.addAll(depQueryDeps);
    ImmutableSet<BuildRule> deps = depsBuilder.build();

    // Inputs we'll be linking (archives, objects, etc.)
    ImmutableList.Builder<Arg> linkInputsBuilder = ImmutableList.builder();
    // Additional linker flags passed to the Haskell linker
    ImmutableList.Builder<Arg> linkFlagsBuilder = ImmutableList.builder();

    CommandTool.Builder executableBuilder = new CommandTool.Builder();

    // Add the binary as the first argument.
    executableBuilder.addArg(SourcePathArg.of(DefaultBuildTargetSourcePath.of(binaryTarget)));

    Path outputDir = BuildTargetPaths.getGenPath(projectFilesystem, binaryTarget, "%s").getParent();
    Path outputPath = outputDir.resolve(binaryTarget.getShortName());

    Path absBinaryDir = projectFilesystem.resolve(outputDir);

    // Special handling for dynamically linked binaries.
    if (depType == Linker.LinkableDepType.SHARED) {

      // Create a symlink tree with for all shared libraries needed by this binary.
      MappedSymlinkTree sharedLibraries =
          graphBuilder.addToIndex(
              CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
                  buildTarget,
                  projectFilesystem,
                  graphBuilder,
                  platform.getCxxPlatform(),
                  deps,
                  r -> Optional.empty()));

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      linkFlagsBuilder.addAll(
          StringArg.from(
              MoreIterables.zipAndConcat(
                  Iterables.cycle("-optl"),
                  Linkers.iXlinker(
                      "-rpath",
                      String.format(
                          "%s/%s",
                          platform
                              .getCxxPlatform()
                              .getLd()
                              .resolve(graphBuilder, buildTarget.getTargetConfiguration())
                              .origin(),
                          absBinaryDir.relativize(sharedLibraries.getRoot()).toString())))));

      // Add all the shared libraries and the symlink tree as inputs to the tool that represents
      // this binary, so that users can attach the proper deps.
      executableBuilder.addNonHashableInput(sharedLibraries.getRootSourcePath());
      executableBuilder.addInputs(sharedLibraries.getLinks().values());
    }

    // Add in linker flags.
    linkFlagsBuilder.addAll(
        ImmutableList.copyOf(
            Iterables.transform(
                args.getLinkerFlags(),
                CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                        buildTarget, cellRoots, graphBuilder, platform.getCxxPlatform())
                    ::convert)));

    // Generate the compile rule and add its objects to the link.
    HaskellCompileRule compileRule =
        graphBuilder.addToIndex(
            HaskellDescriptionUtils.requireCompileRule(
                buildTarget,
                projectFilesystem,
                params,
                graphBuilder,
                RichStream.from(deps)
                    .filter(
                        dep ->
                            dep instanceof HaskellCompileDep || dep instanceof CxxPreprocessorDep)
                    .toImmutableSet(),
                platform,
                depType,
                args.isEnableProfiling(),
                args.getMain(),
                Optional.empty(),
                args.getCompilerFlags(),
                HaskellSources.from(buildTarget, graphBuilder, platform, "srcs", args.getSrcs())));
    linkInputsBuilder.addAll(SourcePathArg.from(compileRule.getObjects()));

    ImmutableList<Arg> linkInputs = linkInputsBuilder.build();
    ImmutableList<Arg> linkFlags = linkFlagsBuilder.build();

    CommandTool executable = executableBuilder.build();
    HaskellLinkRule linkRule =
        HaskellDescriptionUtils.createLinkRule(
            binaryTarget,
            projectFilesystem,
            params,
            graphBuilder,
            platform,
            Linker.LinkType.EXECUTABLE,
            linkFlags,
            linkInputs,
            RichStream.from(deps).filter(NativeLinkableGroup.class).toImmutableList(),
            args.getLinkDepsQueryWhole()
                ? RichStream.from(depQueryDeps).map(BuildRule::getBuildTarget).toImmutableSet()
                : ImmutableSet.of(),
            depType,
            outputPath,
            Optional.empty(),
            args.isEnableProfiling());

    return new HaskellBinary(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(linkRule),
        deps,
        executable,
        linkRule.getSourcePathToOutput());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractHaskellBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    HaskellDescriptionUtils.getParseTimeDeps(
        buildTarget.getTargetConfiguration(),
        ImmutableList.of(getPlatform(buildTarget, constructorArg)),
        targetGraphOnlyDepsBuilder);

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(targetGraphOnlyDepsBuilder::add));
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    if (getHaskellPlatformsProvider(toolchainTargetConfiguration)
        .getHaskellPlatforms()
        .containsAnyOf(flavors)) {
      return true;
    }

    for (Type type : Type.values()) {
      if (flavors.contains(type.getFlavor())) {
        return true;
      }
    }

    return false;
  }

  private HaskellPlatformsProvider getHaskellPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        HaskellPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        HaskellPlatformsProvider.class);
  }

  protected enum Type implements FlavorConvertible {
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR, Linker.LinkableDepType.SHARED),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR, Linker.LinkableDepType.STATIC_PIC),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR, Linker.LinkableDepType.STATIC),
    GHCI(HaskellDescriptionUtils.GHCI_FLAV, Linker.LinkableDepType.STATIC),
    ;

    private final Flavor flavor;
    private final Linker.LinkableDepType linkStyle;

    Type(Flavor flavor, Linker.LinkableDepType linkStyle) {
      this.flavor = flavor;
      this.linkStyle = linkStyle;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

    public Linker.LinkableDepType getLinkStyle() {
      return linkStyle;
    }
  }

  @RuleArg
  interface AbstractHaskellBinaryDescriptionArg extends BuildRuleArg, HasDepsQuery {

    @Value.Default
    default SourceSortedSet getSrcs() {
      return SourceSortedSet.EMPTY;
    }

    ImmutableList<String> getCompilerFlags();

    ImmutableList<StringWithMacros> getLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default boolean getLinkDepsQueryWhole() {
      return false;
    }

    Optional<String> getMain();

    Optional<Linker.LinkableDepType> getLinkStyle();

    Optional<Flavor> getPlatform();

    @Value.Default
    default boolean isEnableProfiling() {
      return false;
    }

    @Value.Default
    default ImmutableSortedSet<BuildTarget> getGhciPreloadDeps() {
      return ImmutableSortedSet.of();
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getGhciPlatformPreloadDeps() {
      return PatternMatchedCollection.of();
    }

    @Override
    default HaskellBinaryDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (HaskellBinaryDescriptionArg) this;
      }
      return HaskellBinaryDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }
}
