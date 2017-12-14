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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class Omnibus {

  private static final Flavor OMNIBUS_FLAVOR = InternalFlavor.of("omnibus");
  private static final Flavor DUMMY_OMNIBUS_FLAVOR = InternalFlavor.of("dummy-omnibus");

  private Omnibus() {}

  private static String getOmnibusSoname(CxxPlatform cxxPlatform) {
    return String.format("libomnibus.%s", cxxPlatform.getSharedLibraryExtension());
  }

  private static BuildTarget getRootTarget(BuildTarget base, BuildTarget root) {
    return base.withAppendedFlavors(
        InternalFlavor.of(Flavor.replaceInvalidCharacters(root.toString())));
  }

  private static BuildTarget getDummyRootTarget(BuildTarget root) {
    return root.withAppendedFlavors(InternalFlavor.of("dummy"));
  }

  private static boolean shouldCreateDummyRoot(NativeLinkTarget target, CxxPlatform cxxPlatform) {
    return target.getNativeLinkTargetMode(cxxPlatform).getType() == Linker.LinkType.EXECUTABLE;
  }

  private static Iterable<NativeLinkable> getDeps(
      NativeLinkable nativeLinkable, CxxPlatform cxxPlatform) {
    return Iterables.concat(
        nativeLinkable.getNativeLinkableDepsForPlatform(cxxPlatform),
        nativeLinkable.getNativeLinkableExportedDepsForPlatform(cxxPlatform));
  }

  // Returned the dependencies for the given node, which can either be a `NativeLinkable` or a
  // `NativeLinkTarget`.
  private static Iterable<? extends NativeLinkable> getDeps(
      BuildTarget target,
      Map<BuildTarget, ? extends NativeLinkTarget> nativeLinkTargets,
      Map<BuildTarget, ? extends NativeLinkable> nativeLinkables,
      CxxPlatform cxxPlatform) {
    if (nativeLinkables.containsKey(target)) {
      NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
      return getDeps(nativeLinkable, cxxPlatform);
    } else {
      NativeLinkTarget nativeLinkTarget = Preconditions.checkNotNull(nativeLinkTargets.get(target));
      return nativeLinkTarget.getNativeLinkTargetDeps(cxxPlatform);
    }
  }

  // Build the data structure containing bookkeeping which describing the omnibus link for the
  // given included and excluded roots.
  static OmnibusSpec buildSpec(
      final CxxPlatform cxxPlatform,
      final Iterable<? extends NativeLinkTarget> includedRoots,
      final Iterable<? extends NativeLinkable> excludedRoots) {

    // A map of targets to native linkable objects.  We maintain this, so that we index our
    // bookkeeping around `BuildTarget` and avoid having to guarantee that all other types are
    // hashable.
    final Map<BuildTarget, NativeLinkable> nativeLinkables = new LinkedHashMap<>();

    // The nodes which should *not* be included in the omnibus link.
    final Set<BuildTarget> excluded = new LinkedHashSet<>();

    // Process all the roots included in the omnibus link.
    final Map<BuildTarget, NativeLinkTarget> roots = new LinkedHashMap<>();
    Map<BuildTarget, NativeLinkable> rootDeps = new LinkedHashMap<>();
    for (NativeLinkTarget root : includedRoots) {
      roots.put(root.getBuildTarget(), root);
      for (NativeLinkable dep :
          NativeLinkables.getNativeLinkables(
                  cxxPlatform,
                  root.getNativeLinkTargetDeps(cxxPlatform),
                  Linker.LinkableDepType.SHARED)
              .values()) {
        Linker.LinkableDepType linkStyle =
            NativeLinkables.getLinkStyle(
                dep.getPreferredLinkage(cxxPlatform), Linker.LinkableDepType.SHARED);
        Preconditions.checkState(linkStyle != Linker.LinkableDepType.STATIC);

        // We only consider deps which aren't *only* statically linked.
        if (linkStyle == Linker.LinkableDepType.SHARED) {
          rootDeps.put(dep.getBuildTarget(), dep);
          nativeLinkables.put(dep.getBuildTarget(), dep);
        }
      }
    }

    // Process all roots excluded from the omnibus link, and add them to our running list of
    // excluded nodes.
    for (NativeLinkable root : excludedRoots) {
      nativeLinkables.put(root.getBuildTarget(), root);
      excluded.add(root.getBuildTarget());
    }

    // Perform the first walk starting from the native linkable nodes immediately reachable via the
    // included roots.  We'll accomplish two things here:
    // 1. Build up the map of node names to their native linkable objects.
    // 2. Perform an initial discovery of dependency nodes to exclude from the omnibus link.
    new AbstractBreadthFirstTraversal<BuildTarget>(rootDeps.keySet()) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) {
        NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
        ImmutableMap<BuildTarget, NativeLinkable> deps =
            Maps.uniqueIndex(getDeps(nativeLinkable, cxxPlatform), NativeLinkable::getBuildTarget);
        nativeLinkables.putAll(deps);
        if (!nativeLinkable.supportsOmnibusLinking(cxxPlatform)) {
          excluded.add(target);
        }
        return deps.keySet();
      }
    }.start();

    // Do another walk to flesh out the transitively excluded nodes.
    new AbstractBreadthFirstTraversal<BuildTarget>(excluded) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) {
        NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
        ImmutableMap<BuildTarget, NativeLinkable> deps =
            Maps.uniqueIndex(getDeps(nativeLinkable, cxxPlatform), NativeLinkable::getBuildTarget);
        nativeLinkables.putAll(deps);
        excluded.add(target);
        return deps.keySet();
      }
    }.start();

    // And then we can do one last walk to create the actual graph which contain only root and body
    // nodes to include in the omnibus link.
    final MutableDirectedGraph<BuildTarget> graphBuilder = new MutableDirectedGraph<>();
    final Set<BuildTarget> deps = new LinkedHashSet<>();
    new AbstractBreadthFirstTraversal<BuildTarget>(Sets.difference(rootDeps.keySet(), excluded)) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) {
        graphBuilder.addNode(target);
        Set<BuildTarget> keep = new LinkedHashSet<>();
        for (BuildTarget dep :
            Iterables.transform(
                getDeps(target, roots, nativeLinkables, cxxPlatform),
                NativeLinkable::getBuildTarget)) {
          if (excluded.contains(dep)) {
            deps.add(dep);
          } else {
            keep.add(dep);
            graphBuilder.addEdge(target, dep);
          }
        }
        return keep;
      }
    }.start();
    DirectedAcyclicGraph<BuildTarget> graph = new DirectedAcyclicGraph<>(graphBuilder);

    // Since we add all undefined root symbols into the omnibus library, we also need to include
    // any excluded root deps as deps of omnibus, as they may fulfill these undefined symbols.
    // Also add any excluded nodes that are also root dependencies.
    deps.addAll(Sets.intersection(rootDeps.keySet(), excluded));

    return ImmutableOmnibusSpec.builder()
        .graph(graph)
        .roots(roots)
        .body(
            graph
                .getNodes()
                .stream()
                .filter(n -> !roots.containsKey(n))
                .collect(ImmutableMap.toImmutableMap(k -> k, Functions.forMap(nativeLinkables))))
        .deps(Maps.asMap(deps, Functions.forMap(nativeLinkables)))
        .excluded(Maps.asMap(excluded, Functions.forMap(nativeLinkables)))
        .excludedRoots(
            RichStream.from(excludedRoots).map(NativeLinkable::getBuildTarget).toImmutableSet())
        .build();
  }

  // Build a dummy library with the omnibus SONAME.  We'll need this to break any dep cycle between
  // the omnibus roots and the merged omnibus body, by first linking the roots against this
  // dummy lib (ignoring missing symbols), then linking the omnibus body with the roots.
  private static SourcePath createDummyOmnibus(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags) {
    BuildTarget dummyOmnibusTarget = baseTarget.withAppendedFlavors(DUMMY_OMNIBUS_FLAVOR);
    String omnibusSoname = getOmnibusSoname(cxxPlatform);
    CxxLink rule =
        ruleResolver.addToIndex(
            CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
                cxxBuckConfig,
                cxxPlatform,
                projectFilesystem,
                ruleResolver,
                ruleFinder,
                dummyOmnibusTarget,
                BuildTargets.getGenPath(projectFilesystem, dummyOmnibusTarget, "%s")
                    .resolve(omnibusSoname),
                ImmutableMap.of(),
                Optional.of(omnibusSoname),
                extraLdflags,
                cellPathResolver));
    return rule.getSourcePathToOutput();
  }

  // Create a build rule which links the given root node against the merged omnibus library
  // described by the given spec file.
  private static OmnibusRoot createRoot(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec,
      SourcePath omnibus,
      NativeLinkTarget root,
      BuildTarget rootTargetBase,
      Optional<Path> output) {

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add any extra flags to the link.
    argsBuilder.addAll(extraLdflags);

    // Since the dummy omnibus library doesn't actually contain any symbols, make sure the linker
    // won't drop its runtime reference to it.
    argsBuilder.addAll(
        StringArg.from(cxxPlatform.getLd().resolve(ruleResolver).getNoAsNeededSharedLibsFlags()));

    // Since we're linking against a dummy libomnibus, ignore undefined symbols.
    argsBuilder.addAll(
        StringArg.from(cxxPlatform.getLd().resolve(ruleResolver).getIgnoreUndefinedSymbolsFlags()));

    // Add the args for the root link target first.
    NativeLinkableInput input = root.getNativeLinkTargetInput(cxxPlatform);
    argsBuilder.addAll(input.getArgs());

    // Grab a topologically sorted mapping of all the root's deps.
    ImmutableMap<BuildTarget, NativeLinkable> deps =
        NativeLinkables.getNativeLinkables(
            cxxPlatform, root.getNativeLinkTargetDeps(cxxPlatform), Linker.LinkableDepType.SHARED);

    // Now process the dependencies in topological order, to assemble the link line.
    boolean alreadyAddedOmnibusToArgs = false;
    for (Map.Entry<BuildTarget, NativeLinkable> entry : deps.entrySet()) {
      BuildTarget linkableTarget = entry.getKey();
      NativeLinkable nativeLinkable = entry.getValue();
      Linker.LinkableDepType linkStyle =
          NativeLinkables.getLinkStyle(
              nativeLinkable.getPreferredLinkage(cxxPlatform), Linker.LinkableDepType.SHARED);

      // If this dep needs to be linked statically, then we always link it directly.
      if (linkStyle != Linker.LinkableDepType.SHARED) {
        Preconditions.checkState(linkStyle == Linker.LinkableDepType.STATIC_PIC);
        argsBuilder.addAll(nativeLinkable.getNativeLinkableInput(cxxPlatform, linkStyle).getArgs());
        continue;
      }

      // If this dep is another root node, substitute in the custom linked library we built for it.
      if (spec.getRoots().containsKey(linkableTarget)) {
        argsBuilder.add(
            SourcePathArg.of(
                DefaultBuildTargetSourcePath.of(getRootTarget(target, linkableTarget))));
        continue;
      }

      // If we're linking this dep from the body, then we need to link via the giant merged
      // libomnibus instead.
      if (spec.getBody()
          .containsKey(linkableTarget)) { // && linkStyle == Linker.LinkableDepType.SHARED) {
        if (!alreadyAddedOmnibusToArgs) {
          argsBuilder.add(SourcePathArg.of(omnibus));
          alreadyAddedOmnibusToArgs = true;
        }
        continue;
      }

      // Otherwise, this is either an explicitly statically linked or excluded node, so link it
      // normally.
      Preconditions.checkState(spec.getExcluded().containsKey(linkableTarget));
      argsBuilder.addAll(nativeLinkable.getNativeLinkableInput(cxxPlatform, linkStyle).getArgs());
    }

    // Create the root library rule using the arguments assembled above.
    BuildTarget rootTarget = getRootTarget(target, rootTargetBase);
    NativeLinkTargetMode rootTargetMode = root.getNativeLinkTargetMode(cxxPlatform);
    CxxLink rootLinkRule;
    switch (rootTargetMode.getType()) {

        // Link the root as a shared library.
      case SHARED:
        {
          Optional<String> rootSoname = rootTargetMode.getLibraryName();
          rootLinkRule =
              CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
                  cxxBuckConfig,
                  cxxPlatform,
                  projectFilesystem,
                  ruleResolver,
                  ruleFinder,
                  rootTarget,
                  output.orElse(
                      BuildTargets.getGenPath(projectFilesystem, rootTarget, "%s")
                          .resolve(
                              rootSoname.orElse(
                                  String.format(
                                      "%s.%s",
                                      rootTarget.getShortName(),
                                      cxxPlatform.getSharedLibraryExtension())))),
                  ImmutableMap.of(),
                  rootSoname,
                  argsBuilder.build(),
                  cellPathResolver);
          break;
        }

        // Link the root as an executable.
      case EXECUTABLE:
        {
          rootLinkRule =
              CxxLinkableEnhancer.createCxxLinkableBuildRule(
                  cellPathResolver,
                  cxxBuckConfig,
                  cxxPlatform,
                  projectFilesystem,
                  ruleResolver,
                  ruleFinder,
                  rootTarget,
                  output.orElse(
                      BuildTargets.getGenPath(projectFilesystem, rootTarget, "%s")
                          .resolve(rootTarget.getShortName())),
                  ImmutableMap.of(),
                  argsBuilder.build(),
                  Linker.LinkableDepType.SHARED,
                  CxxLinkOptions.of(),
                  Optional.empty());
          break;
        }

        // $CASES-OMITTED$
      default:
        throw new IllegalStateException(
            String.format(
                "%s: unexpected omnibus root type: %s %s",
                target, root.getBuildTarget(), rootTargetMode.getType()));
    }

    CxxLink rootRule = ruleResolver.addToIndex(rootLinkRule);
    return OmnibusRoot.of(rootRule.getSourcePathToOutput());
  }

  private static OmnibusRoot createRoot(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec,
      SourcePath omnibus,
      NativeLinkTarget root) {
    return createRoot(
        buildTarget,
        projectFilesystem,
        cellPathResolver,
        ruleResolver,
        ruleFinder,
        cxxBuckConfig,
        cxxPlatform,
        extraLdflags,
        spec,
        omnibus,
        root,
        root.getBuildTarget(),
        root.getNativeLinkTargetOutputPath(cxxPlatform));
  }

  private static OmnibusRoot createDummyRoot(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec,
      SourcePath omnibus,
      NativeLinkTarget root) {
    return createRoot(
        target,
        projectFilesystem,
        cellPathResolver,
        ruleResolver,
        ruleFinder,
        cxxBuckConfig,
        cxxPlatform,
        extraLdflags,
        spec,
        omnibus,
        root,
        getDummyRootTarget(root.getBuildTarget()),
        Optional.empty());
  }

  private static ImmutableList<Arg> createUndefinedSymbolsArgs(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      Iterable<? extends SourcePath> linkerInputs) {
    SourcePath undefinedSymbolsFile =
        cxxPlatform
            .getSymbolNameTool()
            .createUndefinedSymbolsFile(
                projectFilesystem,
                params,
                ruleResolver,
                ruleFinder,
                buildTarget.withAppendedFlavors(
                    InternalFlavor.of("omnibus-undefined-symbols-file")),
                linkerInputs);
    return cxxPlatform
        .getLd()
        .resolve(ruleResolver)
        .createUndefinedSymbolsLinkerArgs(
            projectFilesystem,
            params,
            ruleResolver,
            ruleFinder,
            buildTarget.withAppendedFlavors(InternalFlavor.of("omnibus-undefined-symbols-args")),
            ImmutableList.of(undefinedSymbolsFile));
  }

  // Create a build rule to link the giant merged omnibus library described by the given spec.
  private static OmnibusLibrary createOmnibus(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec) {

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add extra ldflags to the beginning of the link.
    argsBuilder.addAll(extraLdflags);

    // For roots that aren't dependencies of nodes in the body, we extract their undefined symbols
    // to add to the link so that required symbols get pulled into the merged library.
    List<SourcePath> undefinedSymbolsOnlyRoots = new ArrayList<>();
    for (BuildTarget target :
        Sets.difference(spec.getRoots().keySet(), spec.getGraph().getNodes())) {
      NativeLinkTarget linkTarget = Preconditions.checkNotNull(spec.getRoots().get(target));
      undefinedSymbolsOnlyRoots.add(
          ruleResolver
              .requireRule(
                  getRootTarget(
                      buildTarget,
                      shouldCreateDummyRoot(linkTarget, cxxPlatform)
                          ? getDummyRootTarget(target)
                          : target))
              .getSourcePathToOutput());
    }
    argsBuilder.addAll(
        createUndefinedSymbolsArgs(
            buildTarget,
            projectFilesystem,
            params,
            ruleResolver,
            ruleFinder,
            cxxPlatform,
            undefinedSymbolsOnlyRoots));

    // Walk the graph in topological order, appending each nodes contributions to the link.
    ImmutableList<BuildTarget> targets = TopologicalSort.sort(spec.getGraph()).reverse();
    for (BuildTarget target : targets) {

      // If this is a root, just place the shared library we've linked above onto the link line.
      // We need this so that the linker can grab any undefined symbols from it, and therefore
      // know which symbols to pull in from the body nodes.
      NativeLinkTarget root = spec.getRoots().get(target);
      if (root != null) {
        argsBuilder.add(
            SourcePathArg.of(
                ((CxxLink)
                        ruleResolver.requireRule(getRootTarget(buildTarget, root.getBuildTarget())))
                    .getSourcePathToOutput()));
        continue;
      }

      // Otherwise, this is a body node, and we need to add its static library to the link line,
      // so that the linker can discard unused object files from it.
      NativeLinkable nativeLinkable = Preconditions.checkNotNull(spec.getBody().get(target));
      NativeLinkableInput input =
          NativeLinkables.getNativeLinkableInput(
              cxxPlatform, Linker.LinkableDepType.STATIC_PIC, nativeLinkable);
      argsBuilder.addAll(input.getArgs());
    }

    // We process all excluded omnibus deps last, and just add their components as if this were a
    // normal shared link.
    ImmutableMap<BuildTarget, NativeLinkable> deps =
        NativeLinkables.getNativeLinkables(
            cxxPlatform, spec.getDeps().values(), Linker.LinkableDepType.SHARED);
    for (NativeLinkable nativeLinkable : deps.values()) {
      NativeLinkableInput input =
          NativeLinkables.getNativeLinkableInput(
              cxxPlatform, Linker.LinkableDepType.SHARED, nativeLinkable);
      argsBuilder.addAll(input.getArgs());
    }

    // Create the merged omnibus library using the arguments assembled above.
    BuildTarget omnibusTarget = buildTarget.withAppendedFlavors(OMNIBUS_FLAVOR);
    String omnibusSoname = getOmnibusSoname(cxxPlatform);
    CxxLink omnibusRule =
        ruleResolver.addToIndex(
            CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
                cxxBuckConfig,
                cxxPlatform,
                projectFilesystem,
                ruleResolver,
                ruleFinder,
                omnibusTarget,
                BuildTargets.getGenPath(projectFilesystem, omnibusTarget, "%s")
                    .resolve(omnibusSoname),
                ImmutableMap.of(),
                Optional.of(omnibusSoname),
                argsBuilder.build(),
                cellPathResolver));

    return OmnibusLibrary.of(omnibusSoname, omnibusRule.getSourcePathToOutput());
  }

  /**
   * An alternate link strategy for languages which need to package native deps up as shared
   * libraries, which only links native nodes which have an explicit edge from non-native code as
   * separate, and statically linking all other native nodes into a single giant shared library.
   * This reduces the number of shared libraries considerably and also allows the linker to throw
   * away a lot of unused object files.
   *
   * @param cellPathResolver
   * @param nativeLinkTargetRoots root nodes which will be included in the omnibus link.
   * @param nativeLinkableRoots root nodes which are to be excluded from the omnibus link.
   * @return a map of shared library names to their containing {@link SourcePath}s.
   */
  public static OmnibusLibraries getSharedLibraries(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      CellPathResolver cellPathResolver,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraOmnibusLdflags,
      Iterable<? extends NativeLinkTarget> nativeLinkTargetRoots,
      Iterable<? extends NativeLinkable> nativeLinkableRoots) {

    OmnibusLibraries.Builder libs = OmnibusLibraries.builder();

    OmnibusSpec spec = buildSpec(cxxPlatform, nativeLinkTargetRoots, nativeLinkableRoots);

    // Create an empty dummy omnibus library, to give the roots something to link against before
    // we have the actual omnibus library available.  Note that this requires that the linker
    // supports linking shared libraries with undefined references.
    SourcePath dummyOmnibus =
        createDummyOmnibus(
            buildTarget,
            projectFilesystem,
            cellPathResolver,
            ruleResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            extraOmnibusLdflags);

    // Create rule for each of the root nodes, linking against the dummy omnibus library above.
    for (NativeLinkTarget target : spec.getRoots().values()) {

      // For executable roots, some platforms can't properly build them when there are any
      // unresolved symbols, so we initially link a dummy root just to provide a way to grab the
      // undefined symbol list we need to build the real omnibus library.
      if (shouldCreateDummyRoot(target, cxxPlatform)) {
        createDummyRoot(
            buildTarget,
            projectFilesystem,
            cellPathResolver,
            ruleResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            ImmutableList.of(),
            spec,
            dummyOmnibus,
            target);
      } else {
        OmnibusRoot root =
            createRoot(
                buildTarget,
                projectFilesystem,
                cellPathResolver,
                ruleResolver,
                ruleFinder,
                cxxBuckConfig,
                cxxPlatform,
                ImmutableList.of(),
                spec,
                dummyOmnibus,
                target);
        libs.putRoots(target.getBuildTarget(), root);
      }
    }

    // If there are any body nodes, generate the giant merged omnibus library.
    Optional<SourcePath> realOmnibus = Optional.empty();
    if (!spec.getBody().isEmpty()) {
      OmnibusLibrary omnibus =
          createOmnibus(
              buildTarget,
              projectFilesystem,
              cellPathResolver,
              params,
              ruleResolver,
              ruleFinder,
              cxxBuckConfig,
              cxxPlatform,
              extraOmnibusLdflags,
              spec);
      libs.addLibraries(omnibus);
      realOmnibus = Optional.of(omnibus.getPath());
    }

    // Do another pass over executable roots, building the real DSO which links to the real omnibus.
    // See the comment above in the first pass for more details.
    for (NativeLinkTarget target : spec.getRoots().values()) {
      if (shouldCreateDummyRoot(target, cxxPlatform)) {
        OmnibusRoot root =
            createRoot(
                buildTarget,
                projectFilesystem,
                cellPathResolver,
                ruleResolver,
                ruleFinder,
                cxxBuckConfig,
                cxxPlatform,
                ImmutableList.of(),
                spec,
                realOmnibus.orElse(dummyOmnibus),
                target);
        libs.putRoots(target.getBuildTarget(), root);
      }
    }

    // Lastly, add in any shared libraries from excluded nodes the normal way, omitting non-root
    // static libraries.
    for (NativeLinkable nativeLinkable : spec.getExcluded().values()) {
      if (spec.getExcludedRoots().contains(nativeLinkable.getBuildTarget())
          || nativeLinkable.getPreferredLinkage(cxxPlatform) != NativeLinkable.Linkage.STATIC) {
        for (Map.Entry<String, SourcePath> ent :
            nativeLinkable.getSharedLibraries(cxxPlatform).entrySet()) {
          libs.addLibraries(OmnibusLibrary.of(ent.getKey(), ent.getValue()));
        }
      }
    }

    return libs.build();
  }

  @Value.Immutable
  abstract static class OmnibusSpec {

    // The graph containing all root and body nodes that are to be included in the omnibus link.
    public abstract DirectedAcyclicGraph<BuildTarget> getGraph();

    // All native roots included in the omnibus.  These will get linked into separate shared
    // libraries which depend on the giant statically linked omnibus body.
    public abstract ImmutableMap<BuildTarget, NativeLinkTarget> getRoots();

    // All native nodes which are to be statically linked into the giant combined shared library.
    public abstract ImmutableMap<BuildTarget, NativeLinkable> getBody();

    // All root native nodes which are not included in the omnibus link.
    public abstract ImmutableSet<BuildTarget> getExcludedRoots();

    // All native nodes which are not included in the omnibus link, as either a root or a body node.
    public abstract ImmutableMap<BuildTarget, NativeLinkable> getExcluded();

    // The subset of excluded nodes which are first-order deps of any root or body nodes.
    public abstract ImmutableMap<BuildTarget, NativeLinkable> getDeps();

    @Value.Check
    public void verify() {

      // Verify that all the graph is composed entirely off root and body nodes.
      Preconditions.checkState(
          ImmutableSet.<BuildTarget>builder()
              .addAll(getRoots().keySet())
              .addAll(getBody().keySet())
              .build()
              .containsAll(getGraph().getNodes()));

      // Verify that the root, body, and excluded nodes are distinct and that deps are a subset
      // of the excluded nodes.
      Preconditions.checkState(
          Sets.intersection(getRoots().keySet(), getBody().keySet()).isEmpty());
      Preconditions.checkState(
          Sets.intersection(getRoots().keySet(), getExcluded().keySet()).isEmpty());
      Preconditions.checkState(
          Sets.intersection(getBody().keySet(), getExcluded().keySet()).isEmpty());
      Preconditions.checkState(getExcluded().keySet().containsAll(getDeps().keySet()));
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractOmnibusRoot {

    @Value.Parameter
    SourcePath getPath();
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractOmnibusLibrary {

    @Value.Parameter
    String getSoname();

    @Value.Parameter
    SourcePath getPath();
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractOmnibusLibraries {

    @Value.Parameter
    public abstract ImmutableMap<BuildTarget, OmnibusRoot> getRoots();

    @Value.Parameter
    public abstract ImmutableList<OmnibusLibrary> getLibraries();
  }
}
