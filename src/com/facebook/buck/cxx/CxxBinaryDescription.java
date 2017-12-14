/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDepsQuery;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class CxxBinaryDescription
    implements Description<CxxBinaryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<CxxBinaryDescription.AbstractCxxBinaryDescriptionArg>,
        ImplicitFlavorsInferringDescription,
        MetadataProvidingDescription<CxxBinaryDescriptionArg>,
        VersionRoot<CxxBinaryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final ImmutableSet<Flavor> declaredPlatforms;
  private final CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors;
  private final CxxBinaryFactory cxxBinaryFactory;

  public CxxBinaryDescription(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors,
      CxxBinaryFactory cxxBinaryFactory) {
    this.toolchainProvider = toolchainProvider;
    this.declaredPlatforms = cxxBuckConfig.getDeclaredPlatforms();
    this.cxxBinaryImplicitFlavors = cxxBinaryImplicitFlavors;
    this.cxxBinaryFactory = cxxBinaryFactory;
  }

  /** @return a {@link HeaderSymlinkTree} for the headers of this C/C++ binary. */
  public static HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxBinaryDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        resolver,
        cxxPlatform,
        CxxDescriptionEnhancer.parseHeaders(
            buildTarget, resolver, ruleFinder, pathResolver, Optional.of(cxxPlatform), args),
        HeaderVisibility.PRIVATE,
        true);
  }

  @Override
  public Class<CxxBinaryDescriptionArg> getConstructorArgType() {
    return CxxBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBinaryDescriptionArg args) {
    return cxxBinaryFactory.createBuildRule(
        buildTarget, projectFilesystem, resolver, cellRoots, args, ImmutableSortedSet.of());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractCxxBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(
        CxxPlatforms.findDepsForTargetFromConstructorArgs(
            getCxxPlatformsProvider(), buildTarget, constructorArg.getDefaultPlatform()));
    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(extraDepsBuilder::add));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(
        ImmutableSet.of(
            // Missing: CXX Compilation Database
            // Missing: CXX Description Enhancer
            // Missing: CXX Infer Enhancer
            getCxxPlatformsProvider().getCxxPlatforms(),
            LinkerMapMode.FLAVOR_DOMAIN,
            StripStyle.FLAVOR_DOMAIN));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> inputFlavors) {
    Set<Flavor> flavors = inputFlavors;

    Set<Flavor> platformFlavors =
        Sets.intersection(
            flavors,
            Sets.union(
                getCxxPlatformsProvider().getCxxPlatforms().getFlavors(), declaredPlatforms));
    if (platformFlavors.size() > 1) {
      return false;
    }
    flavors = Sets.difference(flavors, platformFlavors);

    flavors =
        Sets.difference(
            flavors,
            ImmutableSet.of(
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
                CxxCompilationDatabase.COMPILATION_DATABASE,
                CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
                CxxInferEnhancer.InferFlavors.INFER.getFlavor(),
                CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor(),
                CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor(),
                StripStyle.ALL_SYMBOLS.getFlavor(),
                StripStyle.DEBUGGING_SYMBOLS.getFlavor(),
                StripStyle.NON_GLOBAL_SYMBOLS.getFlavor(),
                LinkerMapMode.NO_LINKER_MAP.getFlavor()));

    return flavors.isEmpty();
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBinaryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      final Class<U> metadataClass) {
    if (!metadataClass.isAssignableFrom(CxxCompilationDatabaseDependencies.class)
        || !buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return Optional.empty();
    }
    return CxxDescriptionEnhancer.createCompilationDatabaseDependencies(
            buildTarget, getCxxPlatformsProvider().getCxxPlatforms(), resolver, args)
        .map(metadataClass::cast);
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    return cxxBinaryImplicitFlavors.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors, Description.getBuildRuleType(this));
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  public interface CommonArg extends LinkableCxxConstructorArg, HasVersionUniverse, HasDepsQuery {
    @Value.Default
    default boolean getLinkDepsQueryWhole() {
      return false;
    }
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractCxxBinaryDescriptionArg extends CxxBinaryDescription.CommonArg {}
}
