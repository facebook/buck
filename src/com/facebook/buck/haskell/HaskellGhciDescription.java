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
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.PrebuiltCxxLibrary;
import com.facebook.buck.cxx.PrebuiltCxxLibraryGroupDescription;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable.Linkage;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables.SharedLibrariesBuilder;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDepsQuery;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class HaskellGhciDescription
    implements Description<HaskellGhciDescriptionArg>,
        ImplicitDepsInferringDescription<HaskellGhciDescription.AbstractHaskellGhciDescriptionArg>,
        VersionPropagator<HaskellGhciDescriptionArg> {

  private static final Logger LOG = Logger.get(HaskellGhciDescription.class);

  private final HaskellPlatform defaultPlatform;
  private final FlavorDomain<HaskellPlatform> platforms;
  private final CxxBuckConfig cxxBuckConfig;

  public HaskellGhciDescription(
      HaskellPlatform defaultPlatform,
      FlavorDomain<HaskellPlatform> platforms,
      CxxBuckConfig cxxBuckConfig) {
    this.defaultPlatform = defaultPlatform;
    this.platforms = platforms;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<HaskellGhciDescriptionArg> getConstructorArgType() {
    return HaskellGhciDescriptionArg.class;
  }

  private boolean isPrebuiltSO(NativeLinkable nativeLinkable, CxxPlatform cxxPlatform) {

    if (nativeLinkable instanceof PrebuiltCxxLibraryGroupDescription.CustomPrebuiltCxxLibrary) {
      return true;
    }

    if (!(nativeLinkable instanceof PrebuiltCxxLibrary)) {
      return false;
    }

    ImmutableMap<String, SourcePath> sharedLibraries =
        nativeLinkable.getSharedLibraries(cxxPlatform);

    for (Map.Entry<String, SourcePath> ent : sharedLibraries.entrySet()) {
      if (!(ent.getValue() instanceof PathSourcePath)) {
        return false;
      }
    }

    return true;
  }

  /**
   * @param omnibusRoots roots of the graph of nodes (including transitive deps) to include in the
   *     omnibus link.
   * @param excludedRoots roots of a the graph of nodes (including transitive deps) that cannot be
   *     included in the omnibus link.
   * @return the {@link HaskellGhciOmnibusSpec} describing the omnibus link.
   */
  private HaskellGhciOmnibusSpec getOmnibusSpec(
      BuildTarget baseTarget,
      CxxPlatform cxxPlatform,
      ImmutableMap<BuildTarget, ? extends NativeLinkable> omnibusRoots,
      ImmutableMap<BuildTarget, ? extends NativeLinkable> excludedRoots) {

    LOG.verbose("%s: omnibus roots: %s", baseTarget, omnibusRoots);
    LOG.verbose("%s: excluded roots: %s", baseTarget, excludedRoots);

    HaskellGhciOmnibusSpec.Builder builder = HaskellGhciOmnibusSpec.builder();

    // Calculate excluded roots/deps, and add them to the link.
    ImmutableMap<BuildTarget, NativeLinkable> transitiveExcludedLinkables =
        NativeLinkables.getTransitiveNativeLinkables(cxxPlatform, excludedRoots.values());
    builder.setExcludedRoots(excludedRoots);
    builder.setExcludedTransitiveDeps(transitiveExcludedLinkables);

    // Calculate the body and first-order deps of omnibus.
    new AbstractBreadthFirstTraversal<NativeLinkable>(omnibusRoots.values()) {
      @Override
      public Iterable<? extends NativeLinkable> visit(NativeLinkable nativeLinkable) {

        // Excluded linkables can't be included in omnibus.
        if (transitiveExcludedLinkables.containsKey(nativeLinkable.getBuildTarget())) {
          builder.putDeps(nativeLinkable.getBuildTarget(), nativeLinkable);
          LOG.verbose(
              "%s: skipping excluded linkable %s", baseTarget, nativeLinkable.getBuildTarget());
          return ImmutableSet.of();
        }

        // We cannot include prebuilt SOs in omnibus.
        //
        // TODO(agallagher): We should also use `NativeLinkable.supportsOmnibusLinking()` to
        // determine if we can include the library, but this will need likely need to be updated for
        // a multi-pass walk first.
        if (isPrebuiltSO(nativeLinkable, cxxPlatform)) {
          builder.putDeps(nativeLinkable.getBuildTarget(), nativeLinkable);
          LOG.verbose("%s: skipping prebuilt SO %s", baseTarget, nativeLinkable.getBuildTarget());
          return ImmutableSet.of();
        }

        // Include C/C++ libs capable of static linking in omnibus.
        //
        // TODO(agallagher): This should probably be *any* `NativeLinkable` that supports omnibus
        // linking.
        if (nativeLinkable instanceof CxxLibrary || nativeLinkable instanceof PrebuiltCxxLibrary) {
          builder.putBody(nativeLinkable.getBuildTarget(), nativeLinkable);
          LOG.verbose(
              "%s: including C/C++ library %s", baseTarget, nativeLinkable.getBuildTarget());
          return Iterables.concat(
              nativeLinkable.getNativeLinkableDepsForPlatform(cxxPlatform),
              nativeLinkable.getNativeLinkableExportedDepsForPlatform(cxxPlatform));
        }

        // Unexpected node.  Can this actually happen?
        //
        // TODO(agallagher): This should probably be an internal error/assertion, as silently
        // dropping libraries at this point will likely result in we're user errors.
        return ImmutableSet.of();
      }
    }.start();

    HaskellGhciOmnibusSpec spec = builder.build();
    LOG.verbose("%s: built omnibus spec %s", spec);
    return spec;
  }

  private NativeLinkableInput getOmnibusNativeLinkableInput(
      BuildTarget baseTarget,
      CxxPlatform cxxPlatform,
      Iterable<NativeLinkable> body,
      Iterable<NativeLinkable> deps) {

    List<NativeLinkableInput> nativeLinkableInputs = new ArrayList<>();

    // Topologically sort the body nodes, so that they're ready to add to the link line.
    ImmutableSet<BuildTarget> bodyTargets =
        RichStream.from(body).map(NativeLinkable::getBuildTarget).toImmutableSet();
    ImmutableMap<BuildTarget, NativeLinkable> topoSortedBody =
        NativeLinkables.getTopoSortedNativeLinkables(
            body,
            nativeLinkable ->
                RichStream.from(
                        Iterables.concat(
                            nativeLinkable.getNativeLinkableExportedDepsForPlatform(cxxPlatform),
                            nativeLinkable.getNativeLinkableDepsForPlatform(cxxPlatform)))
                    .filter(l -> bodyTargets.contains(l.getBuildTarget())));

    // Add the link inputs for all omnibus nodes.
    for (NativeLinkable nativeLinkable : topoSortedBody.values()) {

      // We link C/C++ libraries whole...
      if (nativeLinkable instanceof CxxLibrary) {
        NativeLinkable.Linkage link = nativeLinkable.getPreferredLinkage(cxxPlatform);
        nativeLinkableInputs.add(
            nativeLinkable.getNativeLinkableInput(
                cxxPlatform,
                NativeLinkables.getLinkStyle(link, Linker.LinkableDepType.STATIC_PIC),
                true,
                ImmutableSet.of()));
        LOG.verbose(
            "%s: linking C/C++ library %s whole into omnibus",
            baseTarget, nativeLinkable.getBuildTarget());
        continue;
      }

      // Link prebuilt C/C++ libraries statically.
      if (nativeLinkable instanceof PrebuiltCxxLibrary) {
        nativeLinkableInputs.add(
            NativeLinkables.getNativeLinkableInput(
                cxxPlatform, Linker.LinkableDepType.STATIC_PIC, nativeLinkable));
        LOG.verbose(
            "%s: linking prebuilt C/C++ library %s into omnibus",
            baseTarget, nativeLinkable.getBuildTarget());
        continue;
      }

      throw new IllegalStateException(
          String.format(
              "%s: unexpected rule type in omnibus link %s(%s)",
              baseTarget, nativeLinkable.getClass(), nativeLinkable.getBuildTarget()));
    }

    // Link in omnibus deps dynamically.
    ImmutableMap<BuildTarget, NativeLinkable> depLinkables =
        NativeLinkables.getNativeLinkables(cxxPlatform, deps, LinkableDepType.SHARED);
    for (NativeLinkable linkable : depLinkables.values()) {
      nativeLinkableInputs.add(
          NativeLinkables.getNativeLinkableInput(cxxPlatform, LinkableDepType.SHARED, linkable));
    }

    return NativeLinkableInput.concat(nativeLinkableInputs);
  }

  private synchronized BuildRule requireOmnibusSharedObject(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Iterable<NativeLinkable> body,
      Iterable<NativeLinkable> deps) {
    return resolver.computeIfAbsent(
        BuildTarget.of(
            UnflavoredBuildTarget.of(
                baseTarget.getCellPath(),
                Optional.empty(),
                baseTarget.getBaseName(),
                baseTarget.getShortName() + ".omnibus-shared-object"),
            baseTarget.getFlavors()),
        ruleTarget ->
            CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
                cxxBuckConfig,
                cxxPlatform,
                projectFilesystem,
                resolver,
                new SourcePathRuleFinder(resolver),
                ruleTarget,
                BuildTargets.getGenPath(projectFilesystem, ruleTarget, "%s")
                    .resolve("libghci_dependencies.so"),
                Optional.of("libghci_dependencies.so"),
                getOmnibusNativeLinkableInput(baseTarget, cxxPlatform, body, deps).getArgs()));
  }

  // Return the C/C++ platform to build against.
  private HaskellPlatform getPlatform(BuildTarget target, AbstractHaskellGhciDescriptionArg arg) {

    Optional<HaskellPlatform> flavorPlatform = platforms.getValue(target);
    if (flavorPlatform.isPresent()) {
      return flavorPlatform.get();
    }

    if (arg.getPlatform().isPresent()) {
      return platforms.getValue(arg.getPlatform().get());
    }

    return defaultPlatform;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      final CellPathResolver cellPathResolver,
      HaskellGhciDescriptionArg args) {

    HaskellPlatform platform = getPlatform(buildTarget, args);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableSet.Builder<BuildRule> depsBuilder = ImmutableSet.builder();
    depsBuilder.addAll(
        CxxDeps.builder()
            .addDeps(args.getDeps())
            .addPlatformDeps(args.getPlatformDeps())
            .build()
            .get(resolver, platform.getCxxPlatform()));
    ImmutableSet<BuildRule> deps = depsBuilder.build();

    ImmutableSet.Builder<BuildRule> preloadDepsBuilder = ImmutableSet.builder();
    preloadDepsBuilder.addAll(
        CxxDeps.builder()
            .addDeps(args.getPreloadDeps())
            .addPlatformDeps(args.getPlatformPreloadDeps())
            .build()
            .get(resolver, platform.getCxxPlatform()));
    ImmutableSet<BuildRule> preloadDeps = preloadDepsBuilder.build();

    // Haskell visitor
    ImmutableSet.Builder<HaskellPackage> haskellPackages = ImmutableSet.builder();
    ImmutableSet.Builder<HaskellPackage> prebuiltHaskellPackages = ImmutableSet.builder();
    ImmutableSet.Builder<HaskellPackage> firstOrderHaskellPackages = ImmutableSet.builder();
    AbstractBreadthFirstTraversal<BuildRule> haskellVisitor =
        new AbstractBreadthFirstTraversal<BuildRule>(deps) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {
            ImmutableSet.Builder<BuildRule> traverse = ImmutableSet.builder();
            if (rule instanceof HaskellLibrary || rule instanceof PrebuiltHaskellLibrary) {
              HaskellCompileDep haskellRule = (HaskellCompileDep) rule;
              HaskellCompileInput ci =
                  haskellRule.getCompileInput(
                      platform, Linker.LinkableDepType.STATIC, args.isEnableProfiling());

              if (params.getBuildDeps().contains(rule)) {
                firstOrderHaskellPackages.addAll(ci.getPackages());
              }

              if (rule instanceof HaskellLibrary) {
                haskellPackages.addAll(ci.getPackages());
              } else if (rule instanceof PrebuiltHaskellLibrary) {
                prebuiltHaskellPackages.addAll(ci.getPackages());
              }

              traverse.addAll(haskellRule.getCompileDeps(platform));
            }

            return traverse.build();
          }
        };
    haskellVisitor.start();

    // Build the omnibus composition spec.
    HaskellGhciOmnibusSpec omnibusSpec =
        getOmnibusSpec(
            buildTarget,
            platform.getCxxPlatform(),
            NativeLinkables.getNativeLinkableRoots(
                RichStream.from(deps).filter(NativeLinkable.class).toImmutableList(),
                n ->
                    n instanceof HaskellLibrary || n instanceof PrebuiltHaskellLibrary
                        ? Optional.of(
                            n.getNativeLinkableExportedDepsForPlatform(platform.getCxxPlatform()))
                        : Optional.empty()),
            // The preloaded deps form our excluded roots, which we need to keep them separate from
            // the omnibus library so that they can be `LD_PRELOAD`ed early.
            RichStream.from(preloadDeps)
                .filter(NativeLinkable.class)
                .collect(MoreCollectors.toImmutableMap(NativeLinkable::getBuildTarget, l -> l)));

    // Construct the omnibus shared library.
    BuildRule omnibusSharedObject =
        requireOmnibusSharedObject(
            buildTarget,
            projectFilesystem,
            resolver,
            platform.getCxxPlatform(),
            omnibusSpec.getBody().values(),
            omnibusSpec.getDeps().values());

    // Build up a map of all transitive shared libraries the the monolithic omnibus library depends
    // on (basically, stuff we couldn't statically link in).  At this point, this should *not* be
    // pulling in any excluded deps.
    SharedLibrariesBuilder sharedLibsBuilder = new SharedLibrariesBuilder();
    ImmutableMap<BuildTarget, NativeLinkable> transitiveDeps =
        NativeLinkables.getTransitiveNativeLinkables(
            platform.getCxxPlatform(), omnibusSpec.getDeps().values());
    transitiveDeps
        .values()
        .stream()
        // Skip statically linked libraries.
        .filter(l -> l.getPreferredLinkage(platform.getCxxPlatform()) != Linkage.STATIC)
        .forEach(l -> sharedLibsBuilder.add(platform.getCxxPlatform(), l));
    ImmutableSortedMap<String, SourcePath> sharedLibs = sharedLibsBuilder.build();

    // Build up a set of all transitive preload libs, which are the ones that have been "excluded"
    // from the omnibus link.  These are the ones we need to LD_PRELOAD.
    SharedLibrariesBuilder preloadLibsBuilder = new SharedLibrariesBuilder();
    omnibusSpec
        .getExcludedTransitiveDeps()
        .values()
        .stream()
        // Don't include shared libs for static libraries -- except for preload roots, which we
        // always link dynamically.
        .filter(
            l ->
                l.getPreferredLinkage(platform.getCxxPlatform()) != Linkage.STATIC
                    || omnibusSpec.getExcludedRoots().containsKey(l.getBuildTarget()))
        .forEach(l -> preloadLibsBuilder.add(platform.getCxxPlatform(), l));
    ImmutableSortedMap<String, SourcePath> preloadLibs = preloadLibsBuilder.build();

    HaskellSources srcs =
        HaskellSources.from(
            buildTarget, resolver, pathResolver, ruleFinder, platform, "srcs", args.getSrcs());

    return HaskellGhciRule.from(
        buildTarget,
        projectFilesystem,
        params,
        ruleFinder,
        srcs,
        args.getCompilerFlags(),
        args.getGhciBinDep()
            .map(
                target ->
                    Preconditions.checkNotNull(resolver.getRule(target).getSourcePathToOutput())),
        args.getGhciInit(),
        omnibusSharedObject,
        sharedLibs,
        preloadLibs,
        firstOrderHaskellPackages.build(),
        haskellPackages.build(),
        prebuiltHaskellPackages.build(),
        args.isEnableProfiling(),
        platform.getGhciScriptTemplate().get(),
        platform.getGhciIservScriptTemplate().get(),
        platform.getGhciBinutils().get(),
        platform.getGhciGhc().get(),
        platform.getGhciIServ().get(),
        platform.getGhciIServProf().get(),
        platform.getGhciLib().get(),
        platform.getGhciCxx().get(),
        platform.getGhciCc().get(),
        platform.getGhciCpp().get());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractHaskellGhciDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    HaskellDescriptionUtils.getParseTimeDeps(
        ImmutableList.of(getPlatform(buildTarget, constructorArg)), extraDepsBuilder);

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(extraDepsBuilder::add));
  }

  /** Composition of {@link NativeLinkable}s in the omnibus link. */
  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractHaskellGhciOmnibusSpec {

    // All native nodes which are to be statically linked into the giant combined shared library.
    ImmutableMap<BuildTarget, NativeLinkable> getBody();

    // The subset of excluded nodes which are first-order deps of any root or body nodes.
    ImmutableMap<BuildTarget, NativeLinkable> getDeps();

    // Native root nodes which are to be excluded from omnibus linking.
    ImmutableMap<BuildTarget, NativeLinkable> getExcludedRoots();

    // Transitive native nodes which are to be excluded from omnibus linking.
    ImmutableMap<BuildTarget, NativeLinkable> getExcludedTransitiveDeps();
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractHaskellGhciDescriptionArg extends CommonDescriptionArg, HasDepsQuery {
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
    default boolean isEnableProfiling() {
      return false;
    }

    Optional<BuildTarget> getGhciBinDep();

    Optional<SourcePath> getGhciInit();

    Optional<Flavor> getPlatform();

    @Value.Default
    default ImmutableCollection<BuildTarget> getPreloadDeps() {
      return ImmutableList.of();
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformPreloadDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
