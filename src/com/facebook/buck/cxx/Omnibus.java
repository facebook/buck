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

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.DefaultDirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Omnibus {

  private static final Flavor OMNIBUS_FLAVOR = ImmutableFlavor.of("omnibus");
  private static final Flavor DUMMY_OMNIBUS_FLAVOR = ImmutableFlavor.of("dummy-omnibus");

  private Omnibus() {}

  private static String getOmnibusSoname(CxxPlatform cxxPlatform) {
    return String.format("libomnibus.%s", cxxPlatform.getSharedLibraryExtension());
  }

  private static BuildTarget getRootTarget(BuildTarget base, BuildTarget root) {
    return BuildTarget.builder(base)
        .addFlavors(ImmutableFlavor.of(Flavor.replaceInvalidCharacters(root.toString())))
        .build();
  }

  private static Iterable<NativeLinkable> getDeps(
      NativeLinkable nativeLinkable,
      CxxPlatform cxxPlatform) {
    return Iterables.concat(
        nativeLinkable.getNativeLinkableDeps(cxxPlatform),
        nativeLinkable.getNativeLinkableExportedDeps(cxxPlatform));
  }

  // Returned the dependencies for the given node, which can either be a `NativeLinkable` or a
  // `SharedNativeLinkTarget`.
  private static Iterable<? extends NativeLinkable> getDeps(
      BuildTarget target,
      Map<BuildTarget, ? extends SharedNativeLinkTarget> nativeLinkTargets,
      Map<BuildTarget, ? extends NativeLinkable> nativeLinkables,
      CxxPlatform cxxPlatform) {
    if (nativeLinkables.containsKey(target)) {
      NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
      return getDeps(nativeLinkable, cxxPlatform);
    } else {
      SharedNativeLinkTarget nativeLinkTarget =
          Preconditions.checkNotNull(nativeLinkTargets.get(target));
      return nativeLinkTarget.getSharedNativeLinkTargetDeps(cxxPlatform);
    }
  }

  // Build the data structure containing bookkeeping which describing the omnibus link for the
  // given included and excluded roots.
  protected static OmnibusSpec buildSpec(
      final CxxPlatform cxxPlatform,
      final Iterable<? extends SharedNativeLinkTarget> includedRoots,
      final Iterable<? extends NativeLinkable> excludedRoots) {

    // A map of targets to native linkable objects.  We maintain this, so that we index our
    // bookkeeping around `BuildTarget` and avoid having to guarantee that all other types are
    // hashable.
    final Map<BuildTarget, NativeLinkable> nativeLinkables = new LinkedHashMap<>();

    // The nodes which should *not* be included in the omnibus link.
    final Set<BuildTarget> excluded = new LinkedHashSet<>();

    // Process all the roots included in the omnibus link.
    final Map<BuildTarget, SharedNativeLinkTarget> roots = new LinkedHashMap<>();
    Map<BuildTarget, NativeLinkable> rootDeps = new LinkedHashMap<>();
    for (SharedNativeLinkTarget root : includedRoots) {
      roots.put(root.getBuildTarget(), root);
      for (NativeLinkable dep :
           NativeLinkables.getNativeLinkables(
               cxxPlatform,
               root.getSharedNativeLinkTargetDeps(cxxPlatform),
               Linker.LinkableDepType.SHARED).values()) {
        Linker.LinkableDepType linkStyle =
            NativeLinkables.getLinkStyle(
                dep.getPreferredLinkage(cxxPlatform),
                Linker.LinkableDepType.SHARED);
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
      public ImmutableSet<BuildTarget> visit(BuildTarget target) {
        NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
        ImmutableMap<BuildTarget, NativeLinkable> deps =
            Maps.uniqueIndex(
                getDeps(nativeLinkable, cxxPlatform),
                HasBuildTarget.TO_TARGET);
        nativeLinkables.putAll(deps);
        if (nativeLinkable.getPreferredLinkage(cxxPlatform) == NativeLinkable.Linkage.SHARED) {
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
            Maps.uniqueIndex(
                getDeps(nativeLinkable, cxxPlatform),
                HasBuildTarget.TO_TARGET);
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
             FluentIterable.from(getDeps(target, roots, nativeLinkables, cxxPlatform))
                 .transform(HasBuildTarget.TO_TARGET)) {
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
    DefaultDirectedAcyclicGraph<BuildTarget> graph =
        new DefaultDirectedAcyclicGraph<>(graphBuilder);

    return ImmutableOmnibusSpec.builder()
        .graph(graph)
        .roots(roots)
        .body(
            FluentIterable.from(graph.getNodes())
                .filter(Predicates.not(Predicates.in(roots.keySet())))
                .toMap(Functions.forMap(nativeLinkables)))
        .deps(Maps.asMap(deps, Functions.forMap(nativeLinkables)))
        .excluded(Maps.asMap(excluded, Functions.forMap(nativeLinkables)))
        .build();
  }

  // Build a dummy library with the omnibus SONAME.  We'll need this to break any dep cycle between
  // the omnibus roots and the merged omnibus body, by first linking the roots against this
  // dummy lib (ignoring missing symbols), then linking the omnibus body with the roots.
  private static SourcePath createDummyOmnibus(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags) {
    BuildTarget dummyOmnibusTarget =
        params.getBuildTarget().withAppendedFlavors(DUMMY_OMNIBUS_FLAVOR);
    String omnibusSoname = getOmnibusSoname(cxxPlatform);
    ruleResolver.addToIndex(
        CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
            cxxBuckConfig,
            cxxPlatform,
            params,
            ruleResolver,
            pathResolver,
            dummyOmnibusTarget,
            BuildTargets.getGenPath(params.getProjectFilesystem(), dummyOmnibusTarget, "%s")
                .resolve(omnibusSoname),
            Optional.of(omnibusSoname),
            extraLdflags));
    return new BuildTargetSourcePath(dummyOmnibusTarget);
  }

  // Create a build rule which links the given root node against the merged omnibus library
  // described by the given spec file.
  protected static OmnibusRoot createRoot(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec,
      SourcePath omnibus,
      SharedNativeLinkTarget root)
      throws NoSuchBuildTargetException {

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
    NativeLinkableInput input = root.getSharedNativeLinkTargetInput(cxxPlatform);
    argsBuilder.addAll(input.getArgs());

    // Grab a topologically sorted mapping of all the root's deps.
    ImmutableMap<BuildTarget, NativeLinkable> deps =
        NativeLinkables.getNativeLinkables(
            cxxPlatform,
            root.getSharedNativeLinkTargetDeps(cxxPlatform),
            Linker.LinkableDepType.SHARED);

    // Now process the dependencies in topological order, to assemble the link line.
    boolean alreadyAddedOmnibusToArgs = false;
    for (Map.Entry<BuildTarget, NativeLinkable> entry : deps.entrySet()) {
      BuildTarget target = entry.getKey();
      NativeLinkable nativeLinkable = entry.getValue();
      Linker.LinkableDepType linkStyle =
          NativeLinkables.getLinkStyle(
              nativeLinkable.getPreferredLinkage(cxxPlatform),
              Linker.LinkableDepType.SHARED);

      // If this dep needs to be linked statically, then we always link it directly.
      if (linkStyle != Linker.LinkableDepType.SHARED) {
        Preconditions.checkState(linkStyle == Linker.LinkableDepType.STATIC_PIC);
        argsBuilder.addAll(nativeLinkable.getNativeLinkableInput(cxxPlatform, linkStyle).getArgs());
        continue;
      }

      // If this dep is another root node, substitute in the custom linked library we built for it.
      if (spec.getRoots().containsKey(target)) {
        argsBuilder.add(
            new SourcePathArg(
                pathResolver,
                new BuildTargetSourcePath(
                    getRootTarget(
                        params.getBuildTarget(),
                        target))));
        continue;
      }

      // If we're linking this dep from the body, then we need to link via the giant merged
      // libomnibus instead.
      if (spec.getBody().containsKey(target)) { // && linkStyle == Linker.LinkableDepType.SHARED) {
        if (!alreadyAddedOmnibusToArgs) {
          argsBuilder.add(new SourcePathArg(pathResolver, omnibus));
          alreadyAddedOmnibusToArgs = true;
        }
        continue;
      }

      // Otherwise, this is either an explicitly statically linked or excluded node, so link it
      // normally.
      Preconditions.checkState(spec.getExcluded().containsKey(target));
      argsBuilder.addAll(nativeLinkable.getNativeLinkableInput(cxxPlatform, linkStyle).getArgs());
    }

    // Create the root library rule using the arguments assembled above.
    BuildTarget rootTarget = getRootTarget(params.getBuildTarget(), root.getBuildTarget());
    Optional<String> rootSoname = root.getSharedNativeLinkTargetLibraryName(cxxPlatform);
    ruleResolver.addToIndex(
        CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
            cxxBuckConfig,
            cxxPlatform,
            params,
            ruleResolver,
            pathResolver,
            rootTarget,
            BuildTargets.getGenPath(params.getProjectFilesystem(), rootTarget, "%s")
                .resolve(
                    rootSoname.or(
                        String.format(
                            "%s.%s",
                            rootTarget.getShortName(),
                            cxxPlatform.getSharedLibraryExtension()))),
            rootSoname,
            argsBuilder.build()));

    return OmnibusRoot.of(rootSoname, new BuildTargetSourcePath(rootTarget));
  }

  private static ImmutableList<Arg> createUndefinedSymbolsArgs(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Iterable<? extends SourcePath> linkerInputs) {
    SourcePath undefinedSymbolsFile =
        cxxPlatform.getSymbolNameTool()
            .createUndefinedSymbolsFile(
                params,
                ruleResolver,
                pathResolver,
                params.getBuildTarget().withAppendedFlavors(
                    ImmutableFlavor.of("omnibus-undefined-symbols-file")),
                linkerInputs);
    return cxxPlatform.getLd().resolve(ruleResolver)
        .createUndefinedSymbolsLinkerArgs(
            params,
            ruleResolver,
            pathResolver,
            params.getBuildTarget().withAppendedFlavors(
                ImmutableFlavor.of("omnibus-undefined-symbols-args")),
            ImmutableList.of(undefinedSymbolsFile));
  }

  // Create a build rule to link the giant merged omnibus library described by the given spec.
  protected static OmnibusLibrary createOmnibus(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec)
      throws NoSuchBuildTargetException {

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add extra ldflags to the beginning of the link.
    argsBuilder.addAll(extraLdflags);

    // For roots that aren't dependencies of nodes in the body, we extract their undefined symbols
    // to add to the link so that required symbols get pulled into the merged library.
    List<SourcePath> undefinedSymbolsOnlyRoots = new ArrayList<>();
    for (BuildTarget target :
         Sets.difference(spec.getRoots().keySet(), spec.getGraph().getNodes())) {
      undefinedSymbolsOnlyRoots.add(
          new BuildTargetSourcePath(
              getRootTarget(
                  params.getBuildTarget(),
                  target)));
    }
    argsBuilder.addAll(
        createUndefinedSymbolsArgs(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            undefinedSymbolsOnlyRoots));

    // Walk the graph in topological order, appending each nodes contributions to the link.
    ImmutableList<BuildTarget> targets =
        TopologicalSort.sort(spec.getGraph(), Predicates.<BuildTarget>alwaysTrue()).reverse();
    for (BuildTarget target : targets) {

      // If this is a root, just place the shared library we've linked above onto the link line.
      // We need this so that the linker can grab any undefined symbols from it, and therefore
      // know which symbols to pull in from the body nodes.
      SharedNativeLinkTarget root = spec.getRoots().get(target);
      if (root != null) {
        argsBuilder.add(
            new SourcePathArg(
                pathResolver,
                new BuildTargetSourcePath(
                    getRootTarget(
                        params.getBuildTarget(),
                        root.getBuildTarget()))));
        continue;
      }

      // Otherwise, this is a body node, and we need to add its static library to the link line,
      // so that the linker can discard unused object files from it.
      NativeLinkable nativeLinkable = Preconditions.checkNotNull(spec.getBody().get(target));
      NativeLinkableInput input =
          NativeLinkables.getNativeLinkableInput(
              cxxPlatform,
              Linker.LinkableDepType.STATIC_PIC,
              nativeLinkable);
      argsBuilder.addAll(input.getArgs());
    }

    // We process all excluded omnibus deps last, and just add their components as if this were a
    // normal shared link.
    ImmutableMap<BuildTarget, NativeLinkable> deps =
        NativeLinkables.getNativeLinkables(
            cxxPlatform,
            spec.getDeps().values(),
            Linker.LinkableDepType.SHARED);
    for (NativeLinkable nativeLinkable : deps.values()) {
      NativeLinkableInput input =
          NativeLinkables.getNativeLinkableInput(
              cxxPlatform,
              Linker.LinkableDepType.SHARED,
              nativeLinkable);
      argsBuilder.addAll(input.getArgs());
    }

    // Create the merged omnibus library using the arguments assembled above.
    BuildTarget omnibusTarget = params.getBuildTarget().withAppendedFlavors(OMNIBUS_FLAVOR);
    String omnibusSoname = getOmnibusSoname(cxxPlatform);
    ruleResolver.addToIndex(
        CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
            cxxBuckConfig,
            cxxPlatform,
            params,
            ruleResolver,
            pathResolver,
            omnibusTarget,
            BuildTargets.getGenPath(params.getProjectFilesystem(), omnibusTarget, "%s")
                .resolve(omnibusSoname),
            Optional.of(omnibusSoname),
            argsBuilder.build()));

    return OmnibusLibrary.of(omnibusSoname, new BuildTargetSourcePath(omnibusTarget));
  }

  /**
   * An alternate link strategy for languages which need to package native deps up as shared
   * libraries, which only links native nodes which have an explicit edge from non-native code as
   * separate, and statically linking all other native nodes into a single giant shared library.
   * This reduces the number of shared libraries considerably and also allows the linker to throw
   * away a lot of unused object files.
   *
   * @param nativeLinkTargetRoots root nodes which will be included in the omnibus link.
   * @param nativeLinkableRoots root nodes which are to be excluded from the omnibus link.
   * @return a map of shared library names to their containing {@link SourcePath}s.
   * @throws NoSuchBuildTargetException
   */
  public static OmnibusLibraries getSharedLibraries(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      Iterable<? extends SharedNativeLinkTarget> nativeLinkTargetRoots,
      Iterable<? extends NativeLinkable> nativeLinkableRoots)
      throws NoSuchBuildTargetException {

    OmnibusLibraries.Builder libs = OmnibusLibraries.builder();

    OmnibusSpec spec = buildSpec(cxxPlatform, nativeLinkTargetRoots, nativeLinkableRoots);

    // Create an empty dummy omnibus library, to give the roots something to link against before
    // we have the actual omnibus library available.  Note that this requires that the linker
    // supports linking shared libraries with undefined references.
    SourcePath dummyOmnibus =
        createDummyOmnibus(
            params,
            ruleResolver,
            pathResolver,
            cxxBuckConfig,
            cxxPlatform,
            extraLdflags);

    // Create rule for each of the root nodes, linking against the dummy omnibus library above.
    for (SharedNativeLinkTarget target : spec.getRoots().values()) {
      OmnibusRoot root =
          createRoot(
              params,
              ruleResolver,
              pathResolver,
              cxxBuckConfig,
              cxxPlatform,
              extraLdflags,
              spec,
              dummyOmnibus,
              target);
      libs.putRoots(target.getBuildTarget(), root);
    }

    // If there are any body nodes, generate the giant merged omnibus library.
    if (!spec.getBody().isEmpty()) {
      OmnibusLibrary omnibus =
          createOmnibus(
              params,
              ruleResolver,
              pathResolver,
              cxxBuckConfig,
              cxxPlatform,
              extraLdflags,
              spec);
      libs.addLibraries(omnibus);
    }

    // Lastly, add in any shared libraries from excluded nodes the normal way.
    for (NativeLinkable nativeLinkable : spec.getExcluded().values()) {
      if (nativeLinkable.getPreferredLinkage(cxxPlatform) != NativeLinkable.Linkage.STATIC) {
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
    public abstract DefaultDirectedAcyclicGraph<BuildTarget> getGraph();

    // All native roots included in the omnibus.  These will get linked into separate shared
    // libraries which depend on the giant statically linked omnibus body.
    public abstract ImmutableMap<BuildTarget, SharedNativeLinkTarget> getRoots();

    // All native nodes which are to be statically linked into the giant combined shared library.
    public abstract ImmutableMap<BuildTarget, NativeLinkable> getBody();

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
    Optional<String> getSoname();

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
