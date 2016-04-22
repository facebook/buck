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

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
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
      HaskellBuckConfig haskellBuckConfig,
      final CxxSourceRuleFactory.PicType picType,
      Optional<String> main,
      ImmutableList<SourcePath> sources)
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

    Tool compiler = haskellBuckConfig.getCompiler().resolve(resolver);
    ImmutableList<Arg> args =
        ImmutableList.<Arg>builder()
            .addAll(SourcePathArg.from(pathResolver, sources))
            .build();

    ImmutableList<String> compileFlags =
        ImmutableList.<String>builder()
            .addAll(haskellBuckConfig.getCompilerFlags())
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
                    .addAll(
                        FluentIterable.from(args)
                            .transformAndConcat(Arg.getDepsFunction(pathResolver)))
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        haskellBuckConfig.getCompiler().resolve(resolver),
        compileFlags,
        picType,
        main,
        includes,
        args);
  }

  public static HaskellCompileRule requireCompileRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      HaskellBuckConfig haskellBuckConfig,
      CxxSourceRuleFactory.PicType picType,
      Optional<String> main,
      ImmutableList<SourcePath> srcs)
      throws NoSuchBuildTargetException {

    BuildTarget target =
        params.getBuildTarget().withFlavors(
            cxxPlatform.getFlavor(),
            ImmutableFlavor.of(
                "objects" + (picType == CxxSourceRuleFactory.PicType.PIC ? "-pic" : "")));

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
            haskellBuckConfig,
            picType,
            main,
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
      HaskellBuckConfig haskellBuckConfig,
      Linker.LinkType linkType,
      ImmutableList<String> extraFlags,
      Iterable<Arg> linkerInputs,
      Iterable<? extends NativeLinkable> deps,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {

    Tool linker = haskellBuckConfig.getLinker().resolve(resolver);
    String name = target.getShortName();

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();

    // Add the base flags from the `.buckconfig` first.
    flagsBuilder.addAll(haskellBuckConfig.getLinkerFlags());

    // Pass in the appropriate flags to link a shared library.
    if (linkType.equals(Linker.LinkType.SHARED)) {
      name =
          CxxDescriptionEnhancer.getSharedLibrarySoname(
              Optional.<String>absent(),
              target.withFlavors(),
              cxxPlatform);
      flagsBuilder.add("-shared", "-dynamic");
      flagsBuilder.addAll(
          MoreIterables.zipAndConcat(
              Iterables.cycle("-optl"),
              cxxPlatform.getLd().resolve(resolver).soname(name)));
    }

    // Add in extra flags passed into this function.
    flagsBuilder.addAll(extraFlags);

    // We pass in the linker inputs and all native linkable deps by prefixing with `-optl` so that
    // the args go straight to the linker, and preserve their order.
    argsBuilder.addAll(linkerInputs);
    for (NativeLinkable nativeLinkable :
         NativeLinkables.getNativeLinkables(cxxPlatform, deps, depType).values()) {
      argsBuilder.addAll(
          NativeLinkables.getNativeLinkableInput(cxxPlatform, depType, nativeLinkable).getArgs());
    }

    ImmutableList<Arg> args = argsBuilder.build();
    ImmutableList<String> flags = flagsBuilder.build();

    return new HaskellLinkRule(
        baseParams.copyWithChanges(
            target,
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(linker.getDeps(pathResolver))
                    .addAll(
                        FluentIterable.from(args)
                            .transformAndConcat(Arg.getDepsFunction(pathResolver)))
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        cxxPlatform.getAr(),
        cxxPlatform.getRanlib(),
        linker,
        flags,
        name,
        args,
        haskellBuckConfig.shouldCacheLinks());
  }

  /**
   * @return parse-time deps needed by Haskell descriptions.
   */
  public static Iterable<BuildTarget> getParseTimeDeps(
      HaskellBuckConfig haskellBuckConfig,
      Iterable<CxxPlatform> cxxPlatforms) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Since this description generates haskell link rules, make sure the parsed includes any
    // of the linkers parse time deps.
    deps.addAll(haskellBuckConfig.getLinker().getParseTimeDeps());

    // Since this description generates haskell compile rules, make sure the parsed includes any
    // of the compilers parse time deps.
    deps.addAll(haskellBuckConfig.getCompiler().getParseTimeDeps());

    // We use the C/C++ linker's Linker object to find out how to pass in the soname, so just add
    // all C/C++ platform parse time deps.
    deps.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms));

    return deps.build();
  }

}
