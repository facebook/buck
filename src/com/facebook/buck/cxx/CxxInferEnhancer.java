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

import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

/** Handles infer flavors for {@link CxxLibrary} and {@link CxxBinary}. */
public final class CxxInferEnhancer {

  /** Flavor adorning the individual inter capture rules. */
  static final InternalFlavor INFER_CAPTURE_FLAVOR = InternalFlavor.of("infer-capture");

  /** Flavors affixed to a library or binary rule to run infer. */
  public enum InferFlavors implements FlavorConvertible {
    INFER(InternalFlavor.of("infer")),
    INFER_ANALYZE(InternalFlavor.of("infer-analyze")),
    INFER_CAPTURE_ALL(InternalFlavor.of("infer-capture-all")),
    INFER_CAPTURE_ONLY(InternalFlavor.of("infer-capture-only"));

    private final InternalFlavor flavor;

    InferFlavors(InternalFlavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public InternalFlavor getFlavor() {
      return flavor;
    }

    private static BuildRuleParams paramsWithoutAnyInferFlavor(BuildRuleParams params) {
      BuildRuleParams result = params;
      for (InferFlavors f : values()) {
        result = result.withoutFlavor(f.getFlavor());
      }
      return result;
    }

    private static void checkNoInferFlavors(ImmutableSet<Flavor> flavors) {
      for (InferFlavors f : InferFlavors.values()) {
        Preconditions.checkArgument(
            !flavors.contains(f.getFlavor()),
            "Unexpected infer-related flavor found: %s",
            f.toString());
      }
    }
  }

  public static FlavorDomain<InferFlavors> INFER_FLAVOR_DOMAIN =
      FlavorDomain.from("Infer flavors", InferFlavors.class);

  public static BuildRule requireInferRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      InferBuckConfig inferBuckConfig)
      throws NoSuchBuildTargetException {
    return new CxxInferEnhancer(resolver, cxxBuckConfig, inferBuckConfig, cxxPlatform)
        .requireInferRule(params, args);
  }

  private final BuildRuleResolver ruleResolver;
  private final CxxBuckConfig cxxBuckConfig;
  private final InferBuckConfig inferBuckConfig;
  private final CxxPlatform cxxPlatform;

  private CxxInferEnhancer(
      BuildRuleResolver ruleResolver,
      CxxBuckConfig cxxBuckConfig,
      InferBuckConfig inferBuckConfig,
      CxxPlatform cxxPlatform) {
    this.ruleResolver = ruleResolver;
    this.cxxBuckConfig = cxxBuckConfig;
    this.inferBuckConfig = inferBuckConfig;
    this.cxxPlatform = cxxPlatform;
  }

  private BuildRule requireInferRule(BuildRuleParams params, CxxConstructorArg args)
      throws NoSuchBuildTargetException {
    Optional<InferFlavors> inferFlavor = INFER_FLAVOR_DOMAIN.getValue(params.getBuildTarget());
    Preconditions.checkArgument(
        inferFlavor.isPresent(), "Expected BuildRuleParams to contain infer flavor.");
    switch (inferFlavor.get()) {
      case INFER:
        return requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(params, args);
      case INFER_ANALYZE:
        return requireInferAnalyzeBuildRuleForCxxDescriptionArg(params, args);
      case INFER_CAPTURE_ALL:
        return requireAllTransitiveCaptureBuildRules(params, args);
      case INFER_CAPTURE_ONLY:
        return requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(params, args);
    }
    throw new IllegalStateException(
        "All InferFlavor cases should be handled, got: " + inferFlavor.get());
  }

  private BuildRule requireAllTransitiveCaptureBuildRules(
      BuildRuleParams params, CxxConstructorArg args) throws NoSuchBuildTargetException {

    CxxInferCaptureRulesAggregator aggregator =
        requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(params, args);

    ImmutableSet<CxxInferCapture> captureRules = aggregator.getAllTransitiveCaptures();

    return ruleResolver.addToIndex(
        new CxxInferCaptureTransitive(
            params.copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder().addAll(captureRules).build()),
                ImmutableSortedSet::of),
            captureRules));
  }

  private CxxInferComputeReport requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(
      BuildRuleParams params, CxxConstructorArg args) throws NoSuchBuildTargetException {

    BuildRuleParams cleanParams = InferFlavors.paramsWithoutAnyInferFlavor(params);

    BuildRuleParams paramsWithInferFlavor =
        cleanParams.withAppendedFlavor(InferFlavors.INFER.getFlavor());

    Optional<CxxInferComputeReport> existingRule =
        ruleResolver.getRuleOptionalWithType(
            paramsWithInferFlavor.getBuildTarget(), CxxInferComputeReport.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    CxxInferAnalyze analysisRule =
        requireInferAnalyzeBuildRuleForCxxDescriptionArg(cleanParams, args);
    return createInferReportRule(paramsWithInferFlavor, analysisRule);
  }

  private CxxInferAnalyze requireInferAnalyzeBuildRuleForCxxDescriptionArg(
      BuildRuleParams params, CxxConstructorArg args) throws NoSuchBuildTargetException {

    Flavor inferAnalyze = InferFlavors.INFER_ANALYZE.getFlavor();

    BuildRuleParams paramsWithInferAnalyzeFlavor =
        InferFlavors.paramsWithoutAnyInferFlavor(params).withAppendedFlavor(inferAnalyze);

    Optional<CxxInferAnalyze> existingRule =
        ruleResolver.getRuleOptionalWithType(
            paramsWithInferAnalyzeFlavor.getBuildTarget(), CxxInferAnalyze.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> cxxInferCaptureAndAnalyzeRules =
        requireTransitiveCaptureAndAggregatingRules(
            params, args, inferAnalyze, CxxInferAnalyze.class);

    return createInferAnalyzeRule(paramsWithInferAnalyzeFlavor, cxxInferCaptureAndAnalyzeRules);
  }

  private CxxInferCaptureRulesAggregator requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(
      BuildRuleParams params, CxxConstructorArg args) throws NoSuchBuildTargetException {

    Flavor inferCaptureOnly = InferFlavors.INFER_CAPTURE_ONLY.getFlavor();

    BuildRuleParams paramsWithInferCaptureOnlyFlavor =
        InferFlavors.paramsWithoutAnyInferFlavor(params).withAppendedFlavor(inferCaptureOnly);

    Optional<CxxInferCaptureRulesAggregator> existingRule =
        ruleResolver.getRuleOptionalWithType(
            paramsWithInferCaptureOnlyFlavor.getBuildTarget(),
            CxxInferCaptureRulesAggregator.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator>
        cxxInferCaptureAndAnalyzeRules =
            requireTransitiveCaptureAndAggregatingRules(
                params, args, inferCaptureOnly, CxxInferCaptureRulesAggregator.class);

    return createInferCaptureAggregatorRule(
        paramsWithInferCaptureOnlyFlavor, cxxInferCaptureAndAnalyzeRules);
  }

  private <T extends BuildRule>
      CxxInferCaptureAndAggregatingRules<T> requireTransitiveCaptureAndAggregatingRules(
          BuildRuleParams params,
          CxxConstructorArg args,
          Flavor requiredFlavor,
          Class<T> aggregatingRuleClass)
          throws NoSuchBuildTargetException {
    BuildRuleParams cleanParams = InferFlavors.paramsWithoutAnyInferFlavor(params);

    ImmutableMap<String, CxxSource> sources = collectSources(cleanParams.getBuildTarget(), args);

    ImmutableSet<CxxInferCapture> captureRules =
        requireInferCaptureBuildRules(cleanParams, sources, args);

    ImmutableSet<BuildRule> deps = args.getCxxDeps().get(ruleResolver, cxxPlatform);

    // Build all the transitive dependencies build rules with the Infer's flavor
    ImmutableSet<T> transitiveDepsLibraryRules =
        requireTransitiveDependentLibraries(
            cxxPlatform, deps, requiredFlavor, aggregatingRuleClass);

    return new CxxInferCaptureAndAggregatingRules<>(captureRules, transitiveDepsLibraryRules);
  }

  private ImmutableMap<String, CxxSource> collectSources(
      BuildTarget buildTarget, CxxConstructorArg args) {
    InferFlavors.checkNoInferFlavors(buildTarget.getFlavors());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return CxxDescriptionEnhancer.parseCxxSources(
        buildTarget, ruleResolver, ruleFinder, pathResolver, cxxPlatform, args);
  }

  private <T extends BuildRule> ImmutableSet<T> requireTransitiveDependentLibraries(
      final CxxPlatform cxxPlatform,
      final Iterable<? extends BuildRule> deps,
      final Flavor requiredFlavor,
      final Class<T> ruleClass)
      throws NoSuchBuildTargetException {
    final ImmutableSet.Builder<T> depsBuilder = ImmutableSet.builder();
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule buildRule) throws NoSuchBuildTargetException {
        if (buildRule instanceof CxxLibrary) {
          CxxLibrary library = (CxxLibrary) buildRule;
          depsBuilder.add(
              (ruleClass.cast(library.requireBuildRule(requiredFlavor, cxxPlatform.getFlavor()))));
          return buildRule.getBuildDeps();
        }
        return ImmutableSet.of();
      }
    }.start();
    return depsBuilder.build();
  }

  private ImmutableList<CxxPreprocessorInput> computePreprocessorInputForCxxBinaryDescriptionArg(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.CommonArg args,
      HeaderSymlinkTree headerSymlinkTree,
      Optional<SymlinkTree> sandboxTree)
      throws NoSuchBuildTargetException {
    ImmutableSet<BuildRule> deps = args.getCxxDeps().get(ruleResolver, cxxPlatform);
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        params,
        cxxPlatform,
        deps,
        CxxFlags.getLanguageFlags(
            args.getPreprocessorFlags(),
            args.getPlatformPreprocessorFlags(),
            args.getLangPreprocessorFlags(),
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        args.getFrameworks(),
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform,
            RichStream.from(deps).filter(CxxPreprocessorDep.class::isInstance).toImmutableList()),
        args.getIncludeDirs(),
        sandboxTree);
  }

  private ImmutableList<CxxPreprocessorInput> computePreprocessorInputForCxxLibraryDescriptionArg(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.CommonArg args,
      HeaderSymlinkTree headerSymlinkTree,
      ImmutableList<String> includeDirs,
      Optional<SymlinkTree> sandboxTree)
      throws NoSuchBuildTargetException {
    ImmutableSet<BuildRule> deps = args.getCxxDeps().get(ruleResolver, cxxPlatform);
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        params,
        cxxPlatform,
        deps,
        CxxFlags.getLanguageFlags(
            args.getPreprocessorFlags(),
            args.getPlatformPreprocessorFlags(),
            args.getLangPreprocessorFlags(),
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        ImmutableSet.of(),
        CxxLibraryDescription.getTransitiveCxxPreprocessorInput(
            params, ruleResolver, cxxPlatform, deps),
        includeDirs,
        sandboxTree);
  }

  private ImmutableSet<CxxInferCapture> requireInferCaptureBuildRules(
      final BuildRuleParams params, ImmutableMap<String, CxxSource> sources, CxxConstructorArg args)
      throws NoSuchBuildTargetException {

    InferFlavors.checkNoInferFlavors(params.getBuildTarget().getFlavors());

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            ruleResolver,
            ruleFinder,
            pathResolver,
            Optional.of(cxxPlatform),
            args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.

    boolean shouldCreateHeadersSymlinks = true;
    if (args instanceof CxxLibraryDescription.CommonArg) {
      shouldCreateHeadersSymlinks =
          ((CxxLibraryDescription.CommonArg) args)
              .getXcodePrivateHeadersSymlinks()
              .orElse(cxxPlatform.getPrivateHeadersSymlinksEnabled());
    }
    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            shouldCreateHeadersSymlinks);
    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree = CxxDescriptionEnhancer.createSandboxTree(params, ruleResolver, cxxPlatform);
    }

    ImmutableList<CxxPreprocessorInput> preprocessorInputs;

    if (args instanceof CxxBinaryDescription.CommonArg) {
      preprocessorInputs =
          computePreprocessorInputForCxxBinaryDescriptionArg(
              params,
              cxxPlatform,
              (CxxBinaryDescription.CommonArg) args,
              headerSymlinkTree,
              sandboxTree);
    } else if (args instanceof CxxLibraryDescription.CommonArg) {
      preprocessorInputs =
          computePreprocessorInputForCxxLibraryDescriptionArg(
              params,
              cxxPlatform,
              (CxxLibraryDescription.CommonArg) args,
              headerSymlinkTree,
              args.getIncludeDirs(),
              sandboxTree);
    } else {
      throw new IllegalStateException("Only Binary and Library args supported.");
    }

    CxxSourceRuleFactory factory =
        CxxSourceRuleFactory.of(
            params,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            preprocessorInputs,
            CxxFlags.getLanguageFlags(
                args.getCompilerFlags(),
                args.getPlatformCompilerFlags(),
                args.getLangCompilerFlags(),
                cxxPlatform),
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            CxxSourceRuleFactory.PicType.PDC,
            sandboxTree);
    return factory.requireInferCaptureBuildRules(sources, inferBuckConfig);
  }

  private CxxInferAnalyze createInferAnalyzeRule(
      BuildRuleParams params,
      CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> captureAnalyzeRules) {
    return ruleResolver.addToIndex(
        new CxxInferAnalyze(
            params.copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(captureAnalyzeRules.captureRules)
                        .addAll(captureAnalyzeRules.aggregatingRules)
                        .build()),
                ImmutableSortedSet::of),
            inferBuckConfig,
            captureAnalyzeRules));
  }

  private CxxInferCaptureRulesAggregator createInferCaptureAggregatorRule(
      BuildRuleParams params,
      CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator> captureAggregatorRules) {
    return ruleResolver.addToIndex(
        new CxxInferCaptureRulesAggregator(params, captureAggregatorRules));
  }

  private CxxInferComputeReport createInferReportRule(
      BuildRuleParams buildRuleParams, CxxInferAnalyze analysisToReport) {
    return ruleResolver.addToIndex(
        new CxxInferComputeReport(
            buildRuleParams.copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(analysisToReport.getTransitiveAnalyzeRules())
                        .add(analysisToReport)
                        .build()),
                ImmutableSortedSet::of),
            analysisToReport));
  }
}
