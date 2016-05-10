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
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.util.Map;
import java.util.TreeMap;

public class HaskellDescriptionUtils {

  private HaskellDescriptionUtils() {}

  /**
   * Create a Haskell compile rule that compiles all the given haskell sources in one step and
   * pulls interface files from all transitive haskell dependencies.
   */
  private static HaskellCompileRule createCompileRule(
      BuildTarget target,
      BuildRuleParams baseParams,
      final BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      final CxxPlatform cxxPlatform,
      HaskellConfig haskellConfig,
      final CxxSourceRuleFactory.PicType picType,
      Optional<String> main,
      ImmutableList<String> flags,
      HaskellSources sources)
      throws NoSuchBuildTargetException {

    final Map<BuildTarget, ImmutableList<String>> depFlags = new TreeMap<>();
    final Map<BuildTarget, ImmutableList<SourcePath>> depIncludes = new TreeMap<>();
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(
        baseParams.getDeps()) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        ImmutableSet<BuildRule> deps = empty;
        if (rule instanceof HaskellCompileDep) {
          deps = rule.getDeps();
          HaskellCompileInput compileInput =
              ((HaskellCompileDep) rule).getCompileInput(cxxPlatform, picType);
          depFlags.put(rule.getBuildTarget(), compileInput.getFlags());
          depIncludes.put(rule.getBuildTarget(), compileInput.getIncludes());
        }
        return deps;
      }
    }.start();

    Tool compiler = haskellConfig.getCompiler().resolve(resolver);

    ImmutableList<String> compileFlags =
        ImmutableList.<String>builder()
            .addAll(haskellConfig.getCompilerFlags())
            .addAll(flags)
            .addAll(Iterables.concat(depFlags.values()))
            .build();

    ImmutableList<SourcePath> includes =
        ImmutableList.copyOf(Iterables.concat(depIncludes.values()));

    return new HaskellCompileRule(
        baseParams.copyWithChanges(
            target,
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(compiler.getDeps(pathResolver))
                    .addAll(pathResolver.filterBuildRuleInputs(includes))
                    .addAll(sources.getDeps(pathResolver))
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        haskellConfig.getCompiler().resolve(resolver),
        compileFlags,
        picType,
        main,
        includes,
        sources);
  }

  protected static BuildTarget getCompileBuildTarget(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      CxxSourceRuleFactory.PicType picType) {
    return target.withFlavors(
            cxxPlatform.getFlavor(),
            ImmutableFlavor.of(
                "objects" + (picType == CxxSourceRuleFactory.PicType.PIC ? "-pic" : "")));
  }

  public static HaskellCompileRule requireCompileRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      HaskellConfig haskellConfig,
      CxxSourceRuleFactory.PicType picType,
      Optional<String> main,
      ImmutableList<String> flags,
      HaskellSources srcs)
      throws NoSuchBuildTargetException {

    BuildTarget target = getCompileBuildTarget(params.getBuildTarget(), cxxPlatform, picType);

    // If this rule has already been generated, return it.
    Optional<HaskellCompileRule> existing =
        resolver.getRuleOptionalWithType(target, HaskellCompileRule.class);
    if (existing.isPresent()) {
      return existing.get();
    }

    return resolver.addToIndex(
        HaskellDescriptionUtils.createCompileRule(
            target,
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            haskellConfig,
            picType,
            main,
            flags,
            srcs));
  }

  /**
   * Create a Haskell link rule that links the given inputs to a executable or shared library and
   * pulls in transitive native linkable deps from the given dep roots.
   */
  public static HaskellLinkRule createLinkRule(
      BuildTarget target,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      HaskellConfig haskellConfig,
      Linker.LinkType linkType,
      ImmutableList<String> extraFlags,
      Iterable<Arg> linkerInputs,
      Iterable<? extends NativeLinkable> deps,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {

    Tool linker = haskellConfig.getLinker().resolve(resolver);
    String name = target.getShortName();

    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add the base flags from the `.buckconfig` first.
    argsBuilder.addAll(StringArg.from(haskellConfig.getLinkerFlags()));

    // Pass in the appropriate flags to link a shared library.
    if (linkType.equals(Linker.LinkType.SHARED)) {
      name =
          CxxDescriptionEnhancer.getSharedLibrarySoname(
              Optional.<String>absent(),
              target.withFlavors(),
              cxxPlatform);
      argsBuilder.addAll(StringArg.from("-shared", "-dynamic"));
      argsBuilder.addAll(
          StringArg.from(
              MoreIterables.zipAndConcat(
                  Iterables.cycle("-optl"),
                  cxxPlatform.getLd().resolve(resolver).soname(name))));
    }

    // Add in extra flags passed into this function.
    argsBuilder.addAll(StringArg.from(extraFlags));

    // We pass in the linker inputs and all native linkable deps by prefixing with `-optl` so that
    // the args go straight to the linker, and preserve their order.
    linkerArgsBuilder.addAll(linkerInputs);
    for (NativeLinkable nativeLinkable :
         NativeLinkables.getNativeLinkables(cxxPlatform, deps, depType).values()) {
      linkerArgsBuilder.addAll(
          NativeLinkables.getNativeLinkableInput(cxxPlatform, depType, nativeLinkable).getArgs());
    }

    // Since we use `-optl` to pass all linker inputs directly to the linker, the haskell linker
    // will complain about not having any input files.  So, create a dummy archive with an empty
    // module and pass that in normally to work around this.
    BuildTarget emptyModuleTarget = target.withAppendedFlavors(ImmutableFlavor.of("empty-module"));
    WriteFile emptyModule =
        resolver.addToIndex(
            new WriteFile(
                baseParams.copyWithBuildTarget(emptyModuleTarget),
                pathResolver,
                "module Unused where",
                BuildTargets.getGenPath(emptyModuleTarget, "%s/Unused.hs"),
                /* executable */ false));
    HaskellCompileRule emptyCompiledModule =
        resolver.addToIndex(
            createCompileRule(
                target.withAppendedFlavors(ImmutableFlavor.of("empty-compiled-module")),
                baseParams,
                resolver,
                pathResolver,
                cxxPlatform,
                haskellConfig,
                CxxSourceRuleFactory.PicType.PIC,
                Optional.<String>absent(),
                ImmutableList.<String>of(),
                HaskellSources.builder()
                    .putModuleMap("Unused", new BuildTargetSourcePath(emptyModule.getBuildTarget()))
                    .build()));
    BuildTarget emptyArchiveTarget =
        target.withAppendedFlavors(ImmutableFlavor.of("empty-archive"));
    Archive emptyArchive =
        resolver.addToIndex(
            Archive.from(
                emptyArchiveTarget,
                baseParams,
                pathResolver,
                cxxPlatform.getAr(),
                cxxPlatform.getRanlib(),
                Archive.Contents.NORMAL,
                BuildTargets.getGenPath(emptyArchiveTarget, "%s/libempty.a"),
                emptyCompiledModule.getObjects()));
    argsBuilder.add(
        new SourcePathArg(pathResolver, new BuildTargetSourcePath(emptyArchive.getBuildTarget())));

    ImmutableList<Arg> args = argsBuilder.build();
    ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    return resolver.addToIndex(
        new HaskellLinkRule(
            baseParams.copyWithChanges(
                target,
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(linker.getDeps(pathResolver))
                        .addAll(
                            FluentIterable.from(args)
                                .transformAndConcat(Arg.getDepsFunction(pathResolver)))
                        .addAll(
                            FluentIterable.from(linkerArgs)
                                .transformAndConcat(Arg.getDepsFunction(pathResolver)))
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            linker,
            name,
            args,
            linkerArgs,
            haskellConfig.shouldCacheLinks()));
  }

  /**
   * @return parse-time deps needed by Haskell descriptions.
   */
  public static Iterable<BuildTarget> getParseTimeDeps(
      HaskellConfig haskellConfig,
      Iterable<CxxPlatform> cxxPlatforms) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Since this description generates haskell link rules, make sure the parsed includes any
    // of the linkers parse time deps.
    deps.addAll(haskellConfig.getLinker().getParseTimeDeps());

    // Since this description generates haskell compile rules, make sure the parsed includes any
    // of the compilers parse time deps.
    deps.addAll(haskellConfig.getCompiler().getParseTimeDeps());

    // We use the C/C++ linker's Linker object to find out how to pass in the soname, so just add
    // all C/C++ platform parse time deps.
    deps.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms));

    return deps.build();
  }

}
