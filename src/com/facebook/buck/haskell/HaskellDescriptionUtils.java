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

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.CxxSourceTypes;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.ExplicitCxxToolFlags;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

public class HaskellDescriptionUtils {

  private HaskellDescriptionUtils() {}

  static final Flavor PROF = InternalFlavor.of("prof");
  static final ImmutableList<String> PROF_FLAGS =
      ImmutableList.of("-prof", "-osuf", "p_o", "-hisuf", "p_hi");
  static final ImmutableList<String> PIC_FLAGS =
      ImmutableList.of("-dynamic", "-fPIC", "-hisuf", "dyn_hi");

  /**
   * Create a Haskell compile rule that compiles all the given haskell sources in one step and pulls
   * interface files from all transitive haskell dependencies.
   */
  private static HaskellCompileRule createCompileRule(
      BuildTarget target,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      final BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ImmutableSet<BuildRule> deps,
      HaskellPlatform platform,
      final Linker.LinkableDepType depType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      ImmutableList<String> flags,
      HaskellSources sources) {

    CxxPlatform cxxPlatform = platform.getCxxPlatform();

    final Map<BuildTarget, ImmutableList<String>> depFlags = new TreeMap<>();
    final Map<BuildTarget, ImmutableList<SourcePath>> depIncludes = new TreeMap<>();
    final ImmutableSortedMap.Builder<String, HaskellPackage> exposedPackagesBuilder =
        ImmutableSortedMap.naturalOrder();
    final ImmutableSortedMap.Builder<String, HaskellPackage> packagesBuilder =
        ImmutableSortedMap.naturalOrder();
    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();

      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        Iterable<BuildRule> ruleDeps = empty;
        if (rule instanceof HaskellCompileDep) {
          HaskellCompileDep haskellCompileDep = (HaskellCompileDep) rule;
          ruleDeps = haskellCompileDep.getCompileDeps(platform);
          HaskellCompileInput compileInput =
              haskellCompileDep.getCompileInput(platform, depType, hsProfile);
          depFlags.put(rule.getBuildTarget(), compileInput.getFlags());
          depIncludes.put(rule.getBuildTarget(), compileInput.getIncludes());

          // We add packages from first-order deps as expose modules, and transitively included
          // packages as hidden ones.
          boolean firstOrderDep = deps.contains(rule);
          for (HaskellPackage pkg : compileInput.getPackages()) {
            if (firstOrderDep) {
              exposedPackagesBuilder.put(pkg.getInfo().getIdentifier(), pkg);
            } else {
              packagesBuilder.put(pkg.getInfo().getIdentifier(), pkg);
            }
          }
        }
        return ruleDeps;
      }
    }.start();

    Collection<CxxPreprocessorInput> cxxPreprocessorInputs =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, deps);
    ExplicitCxxToolFlags.Builder toolFlagsBuilder = CxxToolFlags.explicitBuilder();
    PreprocessorFlags.Builder ppFlagsBuilder = PreprocessorFlags.builder();
    toolFlagsBuilder.setPlatformFlags(
        StringArg.from(CxxSourceTypes.getPlatformPreprocessFlags(cxxPlatform, CxxSource.Type.C)));
    for (CxxPreprocessorInput input : cxxPreprocessorInputs) {
      ppFlagsBuilder.addAllIncludes(input.getIncludes());
      ppFlagsBuilder.addAllFrameworkPaths(input.getFrameworks());
      toolFlagsBuilder.addAllRuleFlags(input.getPreprocessorFlags().get(CxxSource.Type.C));
    }
    ppFlagsBuilder.setOtherFlags(toolFlagsBuilder.build());
    PreprocessorFlags ppFlags = ppFlagsBuilder.build();

    ImmutableList<String> compileFlags =
        ImmutableList.<String>builder()
            .addAll(platform.getCompilerFlags())
            .addAll(flags)
            .addAll(Iterables.concat(depFlags.values()))
            .build();

    ImmutableList<SourcePath> includes =
        ImmutableList.copyOf(Iterables.concat(depIncludes.values()));

    ImmutableSortedMap<String, HaskellPackage> exposedPackages = exposedPackagesBuilder.build();
    ImmutableSortedMap<String, HaskellPackage> packages = packagesBuilder.build();

    return HaskellCompileRule.from(
        target,
        projectFilesystem,
        baseParams,
        ruleFinder,
        platform.getCompiler().resolve(resolver),
        platform.getHaskellVersion(),
        compileFlags,
        ppFlags,
        cxxPlatform,
        depType == Linker.LinkableDepType.STATIC
            ? CxxSourceRuleFactory.PicType.PDC
            : CxxSourceRuleFactory.PicType.PIC,
        hsProfile,
        main,
        packageInfo,
        includes,
        exposedPackages,
        packages,
        sources,
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.C).resolve(resolver));
  }

  protected static BuildTarget getCompileBuildTarget(
      BuildTarget target,
      HaskellPlatform platform,
      Linker.LinkableDepType depType,
      boolean hsProfile) {

    target =
        target.withFlavors(
            platform.getFlavor(),
            InternalFlavor.of("objects-" + depType.toString().toLowerCase().replace('_', '-')));

    if (hsProfile) {
      target = target.withAppendedFlavors(PROF);
    }

    return target;
  }

  public static HaskellCompileRule requireCompileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ImmutableSet<BuildRule> deps,
      HaskellPlatform platform,
      Linker.LinkableDepType depType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      ImmutableList<String> flags,
      HaskellSources srcs) {

    return (HaskellCompileRule)
        resolver.computeIfAbsent(
            getCompileBuildTarget(buildTarget, platform, depType, hsProfile),
            target ->
                HaskellDescriptionUtils.createCompileRule(
                    target,
                    projectFilesystem,
                    params,
                    resolver,
                    ruleFinder,
                    deps,
                    platform,
                    depType,
                    hsProfile,
                    main,
                    packageInfo,
                    flags,
                    srcs));
  }

  /**
   * Create a Haskell link rule that links the given inputs to a executable or shared library and
   * pulls in transitive native linkable deps from the given dep roots.
   */
  public static HaskellLinkRule createLinkRule(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      HaskellPlatform platform,
      Linker.LinkType linkType,
      ImmutableList<Arg> linkerFlags,
      Iterable<Arg> linkerInputs,
      Iterable<? extends NativeLinkable> deps,
      ImmutableSet<BuildTarget> linkWholeDeps,
      Linker.LinkableDepType depType,
      Path outputPath,
      Optional<String> soname,
      boolean hsProfile) {

    Tool linker = platform.getLinker().resolve(resolver);

    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add the base flags from the `.buckconfig` first.
    argsBuilder.addAll(StringArg.from(platform.getLinkerFlags()));

    // Pass in the appropriate flags to link a shared library.
    if (linkType.equals(Linker.LinkType.SHARED)) {
      argsBuilder.addAll(StringArg.from("-shared", "-dynamic"));
      soname.ifPresent(
          name ->
              argsBuilder.addAll(
                  StringArg.from(
                      MoreIterables.zipAndConcat(
                          Iterables.cycle("-optl"),
                          platform.getCxxPlatform().getLd().resolve(resolver).soname(name)))));
    }

    // Add in extra flags passed into this function.
    argsBuilder.addAll(linkerFlags);

    // We pass in the linker inputs and all native linkable deps by prefixing with `-optl` so that
    // the args go straight to the linker, and preserve their order.
    linkerArgsBuilder.addAll(linkerInputs);
    for (NativeLinkable nativeLinkable :
        NativeLinkables.getNativeLinkables(platform.getCxxPlatform(), deps, depType).values()) {
      NativeLinkable.Linkage link = nativeLinkable.getPreferredLinkage(platform.getCxxPlatform());
      NativeLinkableInput input =
          nativeLinkable.getNativeLinkableInput(
              platform.getCxxPlatform(),
              NativeLinkables.getLinkStyle(link, depType),
              linkWholeDeps.contains(nativeLinkable.getBuildTarget()),
              ImmutableSet.of());
      linkerArgsBuilder.addAll(input.getArgs());
    }

    // Since we use `-optl` to pass all linker inputs directly to the linker, the haskell linker
    // will complain about not having any input files.  So, create a dummy archive with an empty
    // module and pass that in normally to work around this.
    BuildTarget emptyModuleTarget = target.withAppendedFlavors(InternalFlavor.of("empty-module"));
    WriteFile emptyModule =
        resolver.addToIndex(
            new WriteFile(
                emptyModuleTarget,
                projectFilesystem,
                baseParams,
                "module Unused where",
                BuildTargets.getGenPath(projectFilesystem, emptyModuleTarget, "%s/Unused.hs"),
                /* executable */ false));
    HaskellCompileRule emptyCompiledModule =
        resolver.addToIndex(
            createCompileRule(
                target.withAppendedFlavors(InternalFlavor.of("empty-compiled-module")),
                projectFilesystem,
                baseParams,
                resolver,
                ruleFinder,
                // TODO(agallagher): We shouldn't need any deps to compile an empty module, but ghc
                // implicitly tries to load the prelude and in some setups this is provided via a
                // Buck dependency.
                RichStream.from(deps)
                    .filter(BuildRule.class)
                    .toImmutableSortedSet(Ordering.natural()),
                platform,
                depType,
                hsProfile,
                Optional.empty(),
                Optional.empty(),
                ImmutableList.of(),
                HaskellSources.builder()
                    .putModuleMap("Unused", emptyModule.getSourcePathToOutput())
                    .build()));
    BuildTarget emptyArchiveTarget = target.withAppendedFlavors(InternalFlavor.of("empty-archive"));
    Archive emptyArchive =
        resolver.addToIndex(
            Archive.from(
                emptyArchiveTarget,
                projectFilesystem,
                resolver,
                ruleFinder,
                platform.getCxxPlatform(),
                ArchiveContents.NORMAL,
                BuildTargets.getGenPath(projectFilesystem, emptyArchiveTarget, "%s/libempty.a"),
                emptyCompiledModule.getObjects(),
                /* cacheable */ true));
    argsBuilder.add(SourcePathArg.of(emptyArchive.getSourcePathToOutput()));

    ImmutableList<Arg> args = argsBuilder.build();
    ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    return resolver.addToIndex(
        new HaskellLinkRule(
            target,
            projectFilesystem,
            baseParams
                .withDeclaredDeps(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(linker.getDeps(ruleFinder))
                        .addAll(
                            Stream.of(args, linkerArgs)
                                .flatMap(Collection::stream)
                                .flatMap(arg -> arg.getDeps(ruleFinder).stream())
                                .iterator())
                        .build())
                .withoutExtraDeps(),
            linker,
            outputPath,
            args,
            linkerArgs,
            platform.shouldCacheLinks()));
  }

  /** Accumulate parse-time deps needed by Haskell descriptions in depsBuilder. */
  public static void getParseTimeDeps(
      Iterable<HaskellPlatform> platforms, ImmutableCollection.Builder<BuildTarget> depsBuilder) {
    RichStream.from(platforms)
        .forEach(
            platform -> {

              // Since this description generates haskell link/compile/package rules, make sure the
              // parser includes deps for these tools.
              depsBuilder.addAll(platform.getCompiler().getParseTimeDeps());
              depsBuilder.addAll(platform.getLinker().getParseTimeDeps());
              depsBuilder.addAll(platform.getPackager().getParseTimeDeps());

              // We use the C/C++ linker's Linker object to find out how to pass in the soname, so just
              // add all C/C++ platform parse time deps.
              depsBuilder.addAll(CxxPlatforms.getParseTimeDeps(platform.getCxxPlatform()));
            });
  }
}
