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
package com.facebook.buck.android.relinker;

import com.facebook.buck.android.AndroidLinkableMetadata;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRuleDependencyVisitors;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * When linking shared libraries, by default, all symbols are exported from the library. In a
 * particular application, though, many of those symbols may never be used. Ideally, in each apk,
 * each shared library would only export the minimal set of symbols that are used by other libraries
 * in the apk. This would allow the linker to remove any dead code within the library (the linker
 * can strip all code that is unreachable from the set of exported symbols).
 *
 * <p>The native relinker tries to remedy the situation. When enabled for an apk, the native
 * relinker will take the set of libraries in the apk and relink them in reverse order telling the
 * linker to only export those symbols that are referenced by a higher library.
 */
public class NativeRelinker {
  private final BuildRuleParams buildRuleParams;
  private final BuildTarget buildTarget;
  private final SourcePathResolver resolver;
  private final CxxBuckConfig cxxBuckConfig;
  private final ImmutableMap<AndroidLinkableMetadata, SourcePath> relinkedLibs;
  private final ImmutableMap<AndroidLinkableMetadata, SourcePath> relinkedLibsAssets;
  private final ProjectFilesystem projectFilesystem;
  private final SourcePathRuleFinder ruleFinder;
  private final ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms;
  private final ImmutableList<RelinkerRule> rules;
  private final ImmutableList<Pattern> symbolPatternWhitelist;
  private final CellPathResolver cellPathResolver;

  public NativeRelinker(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      CellPathResolver cellPathResolver,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableMap<AndroidLinkableMetadata, SourcePath> linkableLibs,
      ImmutableMap<AndroidLinkableMetadata, SourcePath> linkableLibsAssets,
      ImmutableList<Pattern> symbolPatternWhitelist) {
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.cellPathResolver = cellPathResolver;
    this.ruleFinder = ruleFinder;
    Preconditions.checkArgument(
        !linkableLibs.isEmpty() || !linkableLibsAssets.isEmpty(),
        "There should be at least one native library to relink.");

    this.buildRuleParams = buildRuleParams;
    this.resolver = resolver;
    this.cxxBuckConfig = cxxBuckConfig;
    this.nativePlatforms = nativePlatforms;
    this.symbolPatternWhitelist = symbolPatternWhitelist;

    /*
    When relinking a library, any symbols needed by a (transitive) dependent must continue to be
    exported. As relinking one of those dependents may change the set of symbols that it needs,
    we only need to keep the symbols that are still used after a library is relinked. So, this
    relinking process basically works in the reverse order of the original link process. As each
    library is relinked, we now know the set of symbols that are needed in that library's
    dependencies.

    For linkables that can't be resolved to a BuildRule, we can't tell what libraries that one
    depends on. So, we essentially assume that everything depends on it.
    */

    ImmutableMap.Builder<BuildRule, Pair<TargetCpuType, SourcePath>> ruleMapBuilder =
        ImmutableMap.builder();
    ImmutableSet.Builder<Pair<TargetCpuType, SourcePath>> copiedLibraries = ImmutableSet.builder();

    for (Map.Entry<AndroidLinkableMetadata, SourcePath> entry :
        Iterables.concat(linkableLibs.entrySet(), linkableLibsAssets.entrySet())) {
      SourcePath source = entry.getValue();
      Optional<BuildRule> rule = ruleFinder.getRule(source);
      if (rule.isPresent()) {
        ruleMapBuilder.put(rule.get(), new Pair<>(entry.getKey().getTargetCpuType(), source));
      } else {
        copiedLibraries.add(new Pair<>(entry.getKey().getTargetCpuType(), source));
      }
    }

    ImmutableMap<BuildRule, Pair<TargetCpuType, SourcePath>> ruleMap = ruleMapBuilder.build();
    ImmutableSet<BuildRule> linkableRules = ruleMap.keySet();

    // Now, for every linkable build rule, we need to figure out all the other linkable build rules
    // that could depend on it (or rather, could use symbols from it).

    // This is the sub-graph that includes the linkableRules and all the dependents (including
    // non-linkable rules).
    DirectedAcyclicGraph<BuildRule> graph = getBuildGraph(linkableRules);
    ImmutableList<BuildRule> sortedRules = TopologicalSort.sort(graph);
    // This maps a build rule to every rule in linkableRules that depends on it. This (added to the
    // copied libraries) is the set of linkables that could use a symbol from this build rule.
    ImmutableMap<BuildRule, ImmutableSet<BuildRule>> allDependentsMap =
        getAllDependentsMap(linkableRules, graph, sortedRules);

    ImmutableMap.Builder<SourcePath, SourcePath> pathMap = ImmutableMap.builder();

    // Create the relinker rules for the libraries that couldn't be resolved back to a base rule.
    ImmutableList.Builder<RelinkerRule> relinkRules = ImmutableList.builder();
    for (Pair<TargetCpuType, SourcePath> p : copiedLibraries.build()) {
      // TODO(cjhopman): We shouldn't really need a full RelinkerRule at this point. We know that we
      // are just going to copy it, we could just leave these libraries in place and only calculate
      // the list of needed symbols.
      TargetCpuType cpuType = p.getFirst();
      SourcePath source = p.getSecond();
      RelinkerRule relink = makeRelinkerRule(cpuType, source, ImmutableList.of());
      relinkRules.add(relink);
      pathMap.put(source, relink.getLibFileSourcePath());
    }
    ImmutableList<RelinkerRule> copiedLibrariesRules = relinkRules.build();

    // Process the remaining linkable rules in the reverse sorted order. This makes it easy to refer
    // to the RelinkerRules of dependents.
    Iterable<Pair<TargetCpuType, SourcePath>> sortedPaths =
        FluentIterable.from(sortedRules)
            .filter(linkableRules::contains)
            .transform(Functions.forMap(ruleMap))
            .toList()
            .reverse();
    Map<BuildRule, RelinkerRule> relinkerMap = new HashMap<>();

    for (Pair<TargetCpuType, SourcePath> p : sortedPaths) {
      TargetCpuType cpuType = p.getFirst();
      SourcePath source = p.getSecond();
      BuildRule baseRule = ruleFinder.getRule((BuildTargetSourcePath) source);
      // Relinking this library must keep any of the symbols needed by the libraries from the rules
      // in relinkerDeps.
      ImmutableList<RelinkerRule> relinkerDeps =
          ImmutableList.<RelinkerRule>builder()
              .addAll(copiedLibrariesRules)
              .addAll(
                  Lists.transform(
                      ImmutableList.copyOf(allDependentsMap.get(baseRule)),
                      Functions.forMap(relinkerMap)))
              .build();

      RelinkerRule relink = makeRelinkerRule(cpuType, source, relinkerDeps);
      relinkRules.add(relink);
      pathMap.put(source, relink.getLibFileSourcePath());
      relinkerMap.put(baseRule, relink);
    }

    Function<SourcePath, SourcePath> pathMapper = Functions.forMap(pathMap.build());
    rules = relinkRules.build();
    relinkedLibs = ImmutableMap.copyOf(Maps.transformValues(linkableLibs, pathMapper::apply));
    relinkedLibsAssets =
        ImmutableMap.copyOf(Maps.transformValues(linkableLibsAssets, pathMapper::apply));
  }

  private static DirectedAcyclicGraph<BuildRule> getBuildGraph(Set<BuildRule> rules) {
    // TODO(cjhopman): can this use .in(rules) instead of alwaysTrue()?
    return BuildRuleDependencyVisitors.getBuildRuleDirectedGraphFilteredBy(
        rules, x -> true, x -> true);
  }

  /**
   * Creates a map from every BuildRule to the set of transitive dependents of that BuildRule that
   * are in the linkableRules set.
   */
  private ImmutableMap<BuildRule, ImmutableSet<BuildRule>> getAllDependentsMap(
      Set<BuildRule> linkableRules,
      DirectedAcyclicGraph<BuildRule> graph,
      ImmutableList<BuildRule> sortedRules) {
    Map<BuildRule, ImmutableSet<BuildRule>> allDependentsMap = new HashMap<>();
    // Using the sorted list of rules makes this calculation much simpler. We can just assume that
    // we already know all the dependents of a rules incoming nodes when we are processing that
    // rule.
    for (BuildRule rule : sortedRules.reverse()) {
      ImmutableSet.Builder<BuildRule> transitiveDependents = ImmutableSet.builder();
      for (BuildRule dependent : graph.getIncomingNodesFor(rule)) {
        transitiveDependents.addAll(allDependentsMap.get(dependent));
        if (linkableRules.contains(dependent)) {
          transitiveDependents.add(dependent);
        }
      }
      allDependentsMap.put(rule, transitiveDependents.build());
    }
    return ImmutableMap.copyOf(allDependentsMap);
  }

  private RelinkerRule makeRelinkerRule(
      TargetCpuType cpuType, SourcePath source, ImmutableList<RelinkerRule> relinkerDeps) {
    Function<RelinkerRule, SourcePath> getSymbolsNeeded = RelinkerRule::getSymbolsNeededPath;
    String libname = resolver.getAbsolutePath(source).getFileName().toString();
    BuildRuleParams relinkerParams = buildRuleParams.copyAppendingExtraDeps(relinkerDeps);
    BuildRule baseRule = ruleFinder.getRule(source).orElse(null);
    ImmutableList<Arg> linkerArgs = ImmutableList.of();
    Linker linker = null;
    if (baseRule instanceof CxxLink) {
      CxxLink link = (CxxLink) baseRule;
      linkerArgs = link.getArgs();
      linker = link.getLinker();
    }

    return new RelinkerRule(
        buildTarget.withAppendedFlavors(
            InternalFlavor.of("xdso-dce"),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(cpuType.toString())),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(libname))),
        projectFilesystem,
        relinkerParams,
        resolver,
        cellPathResolver,
        ruleFinder,
        ImmutableSortedSet.copyOf(Lists.transform(relinkerDeps, getSymbolsNeeded::apply)),
        cpuType,
        Preconditions.checkNotNull(nativePlatforms.get(cpuType)).getObjdump(),
        cxxBuckConfig,
        source,
        linker,
        linkerArgs,
        symbolPatternWhitelist);
  }

  public ImmutableMap<AndroidLinkableMetadata, SourcePath> getRelinkedLibs() {
    return relinkedLibs;
  }

  public ImmutableMap<AndroidLinkableMetadata, SourcePath> getRelinkedLibsAssets() {
    return relinkedLibsAssets;
  }

  public ImmutableList<RelinkerRule> getRules() {
    return rules;
  }
}
