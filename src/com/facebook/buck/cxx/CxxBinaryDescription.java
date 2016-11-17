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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.util.Optional;
import java.util.Set;

public class CxxBinaryDescription implements
    Description<CxxBinaryDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<CxxBinaryDescription.Arg>,
    ImplicitFlavorsInferringDescription,
    MetadataProvidingDescription<CxxBinaryDescription.Arg> {

  private final CxxBuckConfig cxxBuckConfig;
  private final InferBuckConfig inferBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxBinaryDescription(
      CxxBuckConfig cxxBuckConfig,
      InferBuckConfig inferBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.inferBuckConfig = inferBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
  }

  /**
   * @return a {@link com.facebook.buck.cxx.HeaderSymlinkTree} for the headers of this C/C++ binary.
   */
  public static <A extends Arg> HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            new SourcePathResolver(resolver),
            Optional.of(cxxPlatform),
            args),
        HeaderVisibility.PRIVATE);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    // We explicitly remove strip flavor from params to make sure rule
    // has the same output regardless if we will strip or not.
    Optional<StripStyle> flavoredStripStyle =
        StripStyle.FLAVOR_DOMAIN.getValue(params.getBuildTarget());
    params = CxxStrip.removeStripStyleFlavorInParams(params, flavoredStripStyle);

    // Extract the platform from the flavor, falling back to the default platform if none are
    // found.
    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(params.getBuildTarget().getFlavors());
    CxxPlatform cxxPlatform = cxxPlatforms
        .getValue(flavors).orElse(defaultCxxPlatform);
    if (flavors.contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR)) {
      flavors = ImmutableSet.copyOf(
          Sets.difference(
              flavors,
              ImmutableSet.of(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR)));
      BuildTarget target = BuildTarget
          .builder(params.getBuildTarget().getUnflavoredBuildTarget())
          .addAllFlavors(flavors)
          .build();
      BuildRuleParams typeParams =
          params.copyWithChanges(
              target,
              params.getDeclaredDeps(),
              params.getExtraDeps());

      return createHeaderSymlinkTreeBuildRule(
          typeParams,
          resolver,
          cxxPlatform,
          args);
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    if (flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      BuildRuleParams paramsWithoutFlavor =
          params.withoutFlavor(CxxCompilationDatabase.COMPILATION_DATABASE);
      CxxLinkAndCompileRules cxxLinkAndCompileRules = CxxDescriptionEnhancer
          .createBuildRulesForCxxBinaryDescriptionArg(
              paramsWithoutFlavor,
              resolver,
              cxxBuckConfig,
              cxxPlatform,
              args,
              flavoredStripStyle);
      return CxxCompilationDatabase.createCompilationDatabase(
          params,
          pathResolver,
          cxxBuckConfig.getPreprocessMode(),
          cxxLinkAndCompileRules.compileRules,
          CxxDescriptionEnhancer.requireTransitiveCompilationDatabaseHeaderSymlinkTreeDeps(
              paramsWithoutFlavor,
              resolver,
              pathResolver,
              cxxPlatform,
              args));
    }

    if (flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          cxxPlatforms.getValue(flavors).isPresent() ?
              params :
              params.withFlavor(defaultCxxPlatform.getFlavor()),
          resolver);
    }

    if (flavors.contains(CxxInferEnhancer.InferFlavors.INFER.get())) {
      return CxxInferEnhancer.requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          pathResolver,
          cxxBuckConfig,
          cxxPlatform,
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    }

    if (flavors.contains(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get())) {
      return CxxInferEnhancer.requireInferAnalyzeBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          pathResolver,
          cxxBuckConfig,
          cxxPlatform,
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    }

    if (flavors.contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get())) {
      return CxxInferEnhancer.requireAllTransitiveCaptureBuildRules(
          params,
          resolver,
          cxxBuckConfig,
          cxxPlatform,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig),
          args);
    }

    if (flavors.contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ONLY.get())) {
      return CxxInferEnhancer.requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          pathResolver,
          cxxBuckConfig,
          cxxPlatform,
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    }

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
            params,
            resolver,
            cxxBuckConfig,
            cxxPlatform,
            args,
            flavoredStripStyle);

    // Return a CxxBinary rule as our representative in the action graph, rather than the CxxLink
    // rule above for a couple reasons:
    //  1) CxxBinary extends BinaryBuildRule whereas CxxLink does not, so the former can be used
    //     as executables for genrules.
    //  2) In some cases, users add dependencies from some rules onto other binary rules, typically
    //     if the binary is executed by some test or library code at test time.  These target graph
    //     deps should *not* become build time dependencies on the CxxLink step, otherwise we'd
    //     have to wait for the dependency binary to link before we could link the dependent binary.
    //     By using another BuildRule, we can keep the original target graph dependency tree while
    //     preventing it from affecting link parallelism.

    params = CxxStrip.restoreStripStyleFlavorInParams(params, flavoredStripStyle);
    CxxBinary cxxBinary = new CxxBinary(
        params.appendExtraDeps(cxxLinkAndCompileRules.executable.getDeps(pathResolver)),
        resolver,
        pathResolver,
        cxxLinkAndCompileRules.getBinaryRule(),
        cxxLinkAndCompileRules.executable,
        args.frameworks,
        args.tests,
        params.getBuildTarget().withoutFlavors(cxxPlatforms.getFlavors()));
    resolver.addToIndex(cxxBinary);
    return cxxBinary;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return findDepsForTargetFromConstructorArgs(
        buildTarget,
        cellRoots,
        constructorArg.linkerFlags,
        constructorArg.platformLinkerFlags.getValues());
  }

  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      ImmutableList<String> linkerFlags,
      ImmutableList<ImmutableList<String>> platformLinkerFlags) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Get any parse time deps from the C/C++ platforms.
    deps.addAll(
        CxxPlatforms.getParseTimeDeps(
            cxxPlatforms
                .getValue(buildTarget.getFlavors()).orElse(defaultCxxPlatform)));

    ImmutableList<ImmutableList<String>> macroStrings =
        ImmutableList.<ImmutableList<String>>builder()
            .add(linkerFlags)
            .addAll(platformLinkerFlags)
            .build();
    for (String macroString : Iterables.concat(macroStrings)) {
      try {
        deps.addAll(
            CxxDescriptionEnhancer.MACRO_HANDLER.extractParseTimeDeps(
                buildTarget,
                cellRoots,
                macroString));
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }

    return deps.build();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> inputFlavors) {
    Set<Flavor> flavors = inputFlavors;

    Set<Flavor> platformFlavors = Sets.intersection(flavors, cxxPlatforms.getFlavors());
    if (platformFlavors.size() > 1) {
      return false;
    }
    flavors = Sets.difference(flavors, platformFlavors);

    flavors = Sets.difference(
        flavors,
        ImmutableSet.of(
            CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
            CxxCompilationDatabase.COMPILATION_DATABASE,
            CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
            CxxInferEnhancer.InferFlavors.INFER.get(),
            CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get(),
            CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get(),
            StripStyle.ALL_SYMBOLS.getFlavor(),
            StripStyle.DEBUGGING_SYMBOLS.getFlavor(),
            StripStyle.NON_GLOBAL_SYMBOLS.getFlavor()));

    return flavors.isEmpty();
  }

  public FlavorDomain<CxxPlatform> getCxxPlatforms() {
    return cxxPlatforms;
  }

  public CxxPlatform getDefaultCxxPlatform() {
    return defaultCxxPlatform;
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      final Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (!metadataClass.isAssignableFrom(CxxCompilationDatabaseDependencies.class) ||
        !buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return Optional.empty();
    }
    return CxxDescriptionEnhancer
        .createCompilationDatabaseDependencies(buildTarget, cxxPlatforms, resolver, args).map(
            metadataClass::cast);
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    return addImplicitFlavorsForRuleTypes(argDefaultFlavors, Description.getBuildRuleType(this));
  }

  public ImmutableSortedSet<Flavor> addImplicitFlavorsForRuleTypes(
      ImmutableSortedSet<Flavor> argDefaultFlavors,
      BuildRuleType... types) {
    Optional<Flavor> platformFlavor = getCxxPlatforms().getFlavor(argDefaultFlavors);

    for (BuildRuleType type : types) {
      ImmutableMap<String, Flavor> libraryDefaults =
          cxxBuckConfig.getDefaultFlavorsForRuleType(type);

      if (!platformFlavor.isPresent()) {
        platformFlavor = Optional.ofNullable(
            libraryDefaults.get(CxxBuckConfig.DEFAULT_FLAVOR_PLATFORM));
      }
    }

    if (platformFlavor.isPresent()) {
      return ImmutableSortedSet.of(platformFlavor.get());
    } else {
      // To avoid changing the output path of binaries built without a flavor,
      // we'll default to no flavor, which implicitly builds the default platform.
      return ImmutableSortedSet.of();
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends LinkableCxxConstructorArg {
  }

}
