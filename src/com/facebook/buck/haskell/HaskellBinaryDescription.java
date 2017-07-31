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

package com.facebook.buck.haskell;

import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.Linker;
import com.facebook.buck.cxx.platform.Linkers;
import com.facebook.buck.cxx.platform.NativeLinkable;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDepsQuery;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class HaskellBinaryDescription
    implements Description<HaskellBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<
            HaskellBinaryDescription.AbstractHaskellBinaryDescriptionArg>,
        Flavored,
        VersionRoot<HaskellBinaryDescriptionArg> {

  private static final FlavorDomain<Type> BINARY_TYPE =
      FlavorDomain.from("Haskell Binary Type", Type.class);

  private final HaskellConfig haskellConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  public HaskellBinaryDescription(
      HaskellConfig haskellConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform) {
    this.haskellConfig = haskellConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
  }

  @Override
  public Class<HaskellBinaryDescriptionArg> getConstructorArgType() {
    return HaskellBinaryDescriptionArg.class;
  }

  private Linker.LinkableDepType getLinkStyle(BuildTarget target, HaskellBinaryDescriptionArg arg) {
    Optional<Type> type = BINARY_TYPE.getValue(target);
    if (type.isPresent()) {
      return type.get().getLinkStyle();
    }
    if (arg.getLinkStyle().isPresent()) {
      return arg.getLinkStyle().get();
    }
    return Linker.LinkableDepType.STATIC;
  }

  // Return the C/C++ platform to build against.
  private CxxPlatform getCxxPlatform(BuildTarget target, HaskellBinaryDescriptionArg arg) {

    Optional<CxxPlatform> flavorPlatform = cxxPlatforms.getValue(target);
    if (flavorPlatform.isPresent()) {
      return flavorPlatform.get();
    }

    if (arg.getCxxPlatform().isPresent()) {
      return cxxPlatforms.getValue(arg.getCxxPlatform().get());
    }

    return defaultCxxPlatform;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      HaskellBinaryDescriptionArg args)
      throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    CxxPlatform cxxPlatform = getCxxPlatform(buildTarget, args);
    Linker.LinkableDepType depType = getLinkStyle(buildTarget, args);

    // The target to use for the link rule.
    BuildTarget binaryTarget = buildTarget.withFlavors(InternalFlavor.of("binary"));

    // Maintain backwards compatibility to ease upgrade flows.
    if (haskellConfig.shouldUsedOldBinaryOutputLocation().orElse(true)) {
      binaryTarget = binaryTarget.withAppendedFlavors(cxxPlatform.getFlavor());
    }

    ImmutableSet.Builder<BuildRule> depsBuilder = ImmutableSet.builder();

    depsBuilder.addAll(
        CxxDeps.builder()
            .addDeps(args.getDeps())
            .addPlatformDeps(args.getPlatformDeps())
            .build()
            .get(resolver, cxxPlatform));

    ImmutableList<BuildRule> depQueryDeps =
        args.getDepsQuery()
            .map(
                query ->
                    Preconditions.checkNotNull(query.getResolvedQuery())
                        .stream()
                        .map(resolver::getRule)
                        .filter(NativeLinkable.class::isInstance))
            .orElse(Stream.of())
            .collect(MoreCollectors.toImmutableList());
    depsBuilder.addAll(depQueryDeps);
    ImmutableSet<BuildRule> deps = depsBuilder.build();

    // Inputs we'll be linking (archives, objects, etc.)
    ImmutableList.Builder<Arg> linkInputsBuilder = ImmutableList.builder();
    // Additional linker flags passed to the Haskell linker
    ImmutableList.Builder<Arg> linkFlagsBuilder = ImmutableList.builder();

    CommandTool.Builder executableBuilder = new CommandTool.Builder();

    // Add the binary as the first argument.
    executableBuilder.addArg(SourcePathArg.of(new DefaultBuildTargetSourcePath(binaryTarget)));

    Path outputDir = BuildTargets.getGenPath(projectFilesystem, binaryTarget, "%s").getParent();
    Path outputPath = outputDir.resolve(binaryTarget.getShortName());

    Path absBinaryDir = buildTarget.getCellPath().resolve(outputDir);

    // Special handling for dynamically linked binaries.
    if (depType == Linker.LinkableDepType.SHARED) {

      // Create a symlink tree with for all shared libraries needed by this binary.
      SymlinkTree sharedLibraries =
          resolver.addToIndex(
              CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
                  buildTarget,
                  projectFilesystem,
                  cxxPlatform,
                  deps,
                  NativeLinkable.class::isInstance));

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
                          cxxPlatform.getLd().resolve(resolver).origin(),
                          absBinaryDir.relativize(sharedLibraries.getRoot()).toString())))));

      // Add all the shared libraries and the symlink tree as inputs to the tool that represents
      // this binary, so that users can attach the proper deps.
      executableBuilder.addDep(sharedLibraries);
      executableBuilder.addInputs(sharedLibraries.getLinks().values());
    }

    // Add in linker flags.
    linkFlagsBuilder.addAll(
        ImmutableList.copyOf(
            Iterables.transform(
                args.getLinkerFlags(),
                f ->
                    CxxDescriptionEnhancer.toStringWithMacrosArgs(
                        buildTarget, cellRoots, resolver, cxxPlatform, f))));

    // Generate the compile rule and add its objects to the link.
    HaskellCompileRule compileRule =
        resolver.addToIndex(
            HaskellDescriptionUtils.requireCompileRule(
                buildTarget,
                projectFilesystem,
                params,
                resolver,
                ruleFinder,
                RichStream.from(deps)
                    .filter(
                        dep ->
                            dep instanceof HaskellCompileDep || dep instanceof CxxPreprocessorDep)
                    .toImmutableSet(),
                cxxPlatform,
                haskellConfig,
                depType,
                false,
                args.getMain(),
                Optional.empty(),
                args.getCompilerFlags(),
                HaskellSources.from(
                    buildTarget,
                    resolver,
                    pathResolver,
                    ruleFinder,
                    cxxPlatform,
                    "srcs",
                    args.getSrcs())));
    linkInputsBuilder.addAll(SourcePathArg.from(compileRule.getObjects()));

    ImmutableList<Arg> linkInputs = linkInputsBuilder.build();
    ImmutableList<Arg> linkFlags = linkFlagsBuilder.build();

    final CommandTool executable = executableBuilder.build();
    final HaskellLinkRule linkRule =
        HaskellDescriptionUtils.createLinkRule(
            binaryTarget,
            projectFilesystem,
            params,
            resolver,
            ruleFinder,
            cxxPlatform,
            haskellConfig,
            Linker.LinkType.EXECUTABLE,
            linkFlags,
            linkInputs,
            RichStream.from(deps).filter(NativeLinkable.class).toImmutableList(),
            args.getLinkDepsQueryWhole()
                ? RichStream.from(depQueryDeps).map(BuildRule::getBuildTarget).toImmutableSet()
                : ImmutableSet.of(),
            depType,
            outputPath,
            Optional.empty(),
            false);

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
      CellPathResolver cellRoots,
      AbstractHaskellBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    HaskellDescriptionUtils.getParseTimeDeps(
        haskellConfig,
        ImmutableList.of(
            cxxPlatforms.getValue(buildTarget.getFlavors()).orElse(defaultCxxPlatform)),
        extraDepsBuilder);

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(extraDepsBuilder::add));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (cxxPlatforms.containsAnyOf(flavors)) {
      return true;
    }

    for (Type type : Type.values()) {
      if (flavors.contains(type.getFlavor())) {
        return true;
      }
    }

    return false;
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  protected enum Type implements FlavorConvertible {
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR, Linker.LinkableDepType.SHARED),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR, Linker.LinkableDepType.STATIC_PIC),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR, Linker.LinkableDepType.STATIC),
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

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractHaskellBinaryDescriptionArg extends CommonDescriptionArg, HasDepsQuery {

    @Value.Default
    default SourceList getSrcs() {
      return SourceList.EMPTY;
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

    Optional<Flavor> getCxxPlatform();
  }
}
