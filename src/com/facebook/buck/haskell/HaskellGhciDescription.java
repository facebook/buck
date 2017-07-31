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

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.PrebuiltCxxLibrary;
import com.facebook.buck.cxx.PrebuiltCxxLibraryGroupDescription;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.Linker;
import com.facebook.buck.cxx.platform.NativeLinkable;
import com.facebook.buck.cxx.platform.NativeLinkableInput;
import com.facebook.buck.cxx.platform.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
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
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class HaskellGhciDescription
    implements Description<HaskellGhciDescriptionArg>,
        ImplicitDepsInferringDescription<HaskellGhciDescription.AbstractHaskellGhciDescriptionArg>,
        VersionPropagator<HaskellGhciDescriptionArg> {

  private final HaskellConfig haskellConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  public HaskellGhciDescription(
      HaskellConfig haskellConfig,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform) {
    this.haskellConfig = haskellConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
  }

  @Override
  public Class<HaskellGhciDescriptionArg> getConstructorArgType() {
    return HaskellGhciDescriptionArg.class;
  }

  public static ImmutableList<NativeLinkable> getSortedNativeLinkables(
      final CxxPlatform cxxPlatform, Iterable<? extends NativeLinkable> inputs) {

    ImmutableMap<BuildTarget, NativeLinkable> nativeLinkableMap =
        NativeLinkables.getNativeLinkables(cxxPlatform, inputs, Linker.LinkableDepType.STATIC_PIC);

    return ImmutableList.copyOf(nativeLinkableMap.values());
  }

  private boolean isPrebuiltSO(NativeLinkable nativeLinkable, CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {

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

  private synchronized BuildRule requireOmnibusSharedObject(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableList<NativeLinkable> sortedNativeLinkables)
      throws NoSuchBuildTargetException {
    return resolver.computeIfAbsentThrowing(
        BuildTarget.of(
            UnflavoredBuildTarget.of(
                baseTarget.getCellPath(),
                Optional.empty(),
                baseTarget.getBaseName(),
                baseTarget.getShortName() + ".omnibus-shared-object"),
            baseTarget.getFlavors()),
        ruleTarget -> {
          ImmutableList.Builder<NativeLinkableInput> nativeLinkableInputs = ImmutableList.builder();

          for (NativeLinkable nativeLinkable : sortedNativeLinkables) {
            if (nativeLinkable instanceof CxxLibrary) {
              NativeLinkable.Linkage link = nativeLinkable.getPreferredLinkage(cxxPlatform);
              nativeLinkableInputs.add(
                  nativeLinkable.getNativeLinkableInput(
                      cxxPlatform,
                      NativeLinkables.getLinkStyle(link, Linker.LinkableDepType.STATIC_PIC),
                      true,
                      ImmutableSet.of()));
            } else if (nativeLinkable instanceof PrebuiltCxxLibrary) {
              if (isPrebuiltSO(nativeLinkable, cxxPlatform)) {
                nativeLinkableInputs.add(
                    NativeLinkables.getNativeLinkableInput(
                        cxxPlatform, Linker.LinkableDepType.SHARED, nativeLinkable));
              } else {
                nativeLinkableInputs.add(
                    NativeLinkables.getNativeLinkableInput(
                        cxxPlatform, Linker.LinkableDepType.STATIC_PIC, nativeLinkable));
              }
            }
          }

          NativeLinkableInput nli = NativeLinkableInput.concat(nativeLinkableInputs.build());
          return CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
              cxxBuckConfig,
              cxxPlatform,
              projectFilesystem,
              resolver,
              new SourcePathRuleFinder(resolver),
              ruleTarget,
              BuildTargets.getGenPath(projectFilesystem, ruleTarget, "%s")
                  .resolve("libghci_dependencies.so"),
              Optional.of("libghci_dependencies.so"),
              nli.getArgs());
        });
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      final CellPathResolver cellPathResolver,
      HaskellGhciDescriptionArg args)
      throws NoSuchBuildTargetException {

    CxxPlatform cxxPlatform = cxxPlatforms.getValue(buildTarget).orElse(defaultCxxPlatform);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableSet.Builder<BuildRule> depsBuilder = ImmutableSet.builder();
    depsBuilder.addAll(
        CxxDeps.builder()
            .addDeps(args.getDeps())
            .addPlatformDeps(args.getPlatformDeps())
            .build()
            .get(resolver, cxxPlatform));
    ImmutableSet<BuildRule> deps = depsBuilder.build();

    ImmutableSet.Builder<HaskellPackage> haskellPackages = ImmutableSet.builder();
    ImmutableSet.Builder<HaskellPackage> prebuiltHaskellPackages = ImmutableSet.builder();
    ImmutableSet.Builder<HaskellPackage> firstOrderHaskellPackages = ImmutableSet.builder();
    AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException> haskellVisitor =
        new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
            ImmutableSet.Builder<BuildRule> traverse = ImmutableSet.builder();
            if (rule instanceof HaskellLibrary || rule instanceof PrebuiltHaskellLibrary) {
              HaskellCompileInput ci =
                  ((HaskellCompileDep) rule)
                      .getCompileInput(
                          cxxPlatform, Linker.LinkableDepType.STATIC, args.getEnableProfiling());

              if (params.getBuildDeps().contains(rule)) {
                firstOrderHaskellPackages.addAll(ci.getPackages());
              }

              if (rule instanceof HaskellLibrary) {
                haskellPackages.addAll(ci.getPackages());
                traverse.addAll(rule.getBuildDeps());
              } else if (rule instanceof PrebuiltHaskellLibrary) {
                prebuiltHaskellPackages.addAll(ci.getPackages());
                traverse.addAll(rule.getBuildDeps());
              }
            }

            return traverse.build();
          }
        };
    haskellVisitor.start();

    ImmutableSet.Builder<NativeLinkable> nativeLinkables = ImmutableSet.builder();
    AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException> cxxVisitor =
        new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
            ImmutableSet.Builder<BuildRule> traverse = ImmutableSet.builder();
            if (rule instanceof CxxLibrary) {
              nativeLinkables.add((NativeLinkable) rule);
            } else if (rule instanceof PrebuiltCxxLibrary) {
              nativeLinkables.add((NativeLinkable) rule);
            } else if (rule instanceof HaskellLibrary || rule instanceof PrebuiltHaskellLibrary) {
              for (NativeLinkable nl :
                  ((NativeLinkable) rule).getNativeLinkableExportedDepsForPlatform(cxxPlatform)) {
                traverse.add((BuildRule) nl);
              }
            }

            return traverse.build();
          }
        };
    cxxVisitor.start();

    ImmutableList<NativeLinkable> sortedNativeLinkables =
        getSortedNativeLinkables(cxxPlatform, nativeLinkables.build());

    BuildRule omnibusSharedObject =
        requireOmnibusSharedObject(
            buildTarget, projectFilesystem, resolver, cxxPlatform, sortedNativeLinkables);

    ImmutableSortedMap.Builder<String, SourcePath> solibs = ImmutableSortedMap.naturalOrder();
    for (NativeLinkable nativeLinkable : sortedNativeLinkables) {
      if (isPrebuiltSO(nativeLinkable, cxxPlatform)) {
        ImmutableMap<String, SourcePath> sharedObjects =
            nativeLinkable.getSharedLibraries(cxxPlatform);
        for (Map.Entry<String, SourcePath> ent : sharedObjects.entrySet()) {
          if (ent.getValue() instanceof PathSourcePath) {
            solibs.put(ent.getKey(), ent.getValue());
          }
        }
      }
    }

    HaskellSources srcs =
        HaskellSources.from(
            buildTarget, resolver, pathResolver, ruleFinder, cxxPlatform, "srcs", args.getSrcs());

    return resolver.addToIndex(
        HaskellGhciRule.from(
            buildTarget,
            projectFilesystem,
            params,
            resolver,
            srcs,
            args.getCompilerFlags(),
            args.getGhciBinDep(),
            args.getGhciInit(),
            omnibusSharedObject,
            solibs.build(),
            firstOrderHaskellPackages.build(),
            haskellPackages.build(),
            prebuiltHaskellPackages.build(),
            args.getEnableProfiling(),
            haskellConfig.getGhciScriptTemplate(),
            haskellConfig.getGhciBinutils(),
            haskellConfig.getGhciGhc(),
            haskellConfig.getGhciLib(),
            haskellConfig.getGhciCxx(),
            haskellConfig.getGhciCc(),
            haskellConfig.getGhciCpp()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractHaskellGhciDescriptionArg constructorArg,
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

  @BuckStyleImmutable
  @Value.Immutable
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
    default boolean getEnableProfiling() {
      return false;
    }

    Optional<BuildTarget> getGhciBinDep();

    Optional<SourcePath> getGhciInit();
  }
}
