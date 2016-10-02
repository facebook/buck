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
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;

public final class CxxInferEnhancer {

  private CxxInferEnhancer() {}

  public enum InferFlavors {
    INFER(ImmutableFlavor.of("infer")),
    INFER_ANALYZE(ImmutableFlavor.of("infer-analyze")),
    INFER_CAPTURE(ImmutableFlavor.of("infer-capture")),
    INFER_CAPTURE_ALL(ImmutableFlavor.of("infer-capture-all")),
    INFER_CAPTURE_ONLY(ImmutableFlavor.of("infer-capture-only"));

    private final ImmutableFlavor flavor;
    InferFlavors(ImmutableFlavor flavor) {
      this.flavor = flavor;
    }

    public ImmutableFlavor get() {
      return flavor;
    }

    public static ImmutableSet<ImmutableFlavor> getAll() {
      ImmutableSet.Builder<ImmutableFlavor> builder = ImmutableSet.builder();
      for (InferFlavors f : values()) {
        builder.add(f.get());
      }
      return builder.build();
    }

    private static BuildRuleParams paramsWithoutAnyInferFlavor(BuildRuleParams params) {
      BuildRuleParams result = params;
      for (InferFlavors f : values()) {
        result = result.withoutFlavor(f.get());
      }
      return result;
    }

    private static void checkNoInferFlavors(ImmutableSet<Flavor> flavors) {
      for (InferFlavors f : InferFlavors.values()) {
        Preconditions.checkArgument(
            !flavors.contains(f.get()),
            "Unexpected infer-related flavor found: %s", f.toString());
      }
    }
  }

  public static ImmutableMap<String, CxxSource> collectSources(
      BuildTarget buildTarget,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args) {
    InferFlavors.checkNoInferFlavors(buildTarget.getFlavors());
    return CxxDescriptionEnhancer.parseCxxSources(
        buildTarget,
        new SourcePathResolver(ruleResolver),
        cxxPlatform,
        args);
  }

  public static BuildRule requireAllTransitiveCaptureBuildRules(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      InferBuckConfig inferBuckConfig,
      CxxInferSourceFilter sourceFilter,
      CxxConstructorArg args) throws NoSuchBuildTargetException {

    CxxInferCaptureRulesAggregator aggregator =
        requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(
            params,
            ruleResolver,
            new SourcePathResolver(ruleResolver),
            cxxBuckConfig,
            cxxPlatform,
            args,
            inferBuckConfig,
            sourceFilter);

    ImmutableSet<CxxInferCapture> captureRules = aggregator.getAllTransitiveCaptures();

    return ruleResolver.addToIndex(
        new CxxInferCaptureTransitive(
            params.copyWithChanges(
                params.getBuildTarget(),
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(captureRules)
                        .build()),
                params.getExtraDeps()),
            new SourcePathResolver(ruleResolver),
            captureRules));
  }

  public static CxxInferComputeReport requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      InferBuckConfig inferConfig,
      CxxInferSourceFilter sourceFilter) throws NoSuchBuildTargetException {

    BuildRuleParams cleanParams = InferFlavors.paramsWithoutAnyInferFlavor(params);

    BuildRuleParams paramsWithInferFlavor = cleanParams.withFlavor(InferFlavors.INFER.get());

    Optional<CxxInferComputeReport> existingRule = resolver.getRuleOptionalWithType(
        paramsWithInferFlavor.getBuildTarget(), CxxInferComputeReport.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    CxxInferAnalyze analysisRule = requireInferAnalyzeBuildRuleForCxxDescriptionArg(
        cleanParams,
        resolver,
        pathResolver,
        cxxBuckConfig,
        cxxPlatform,
        args,
        inferConfig,
        sourceFilter);
    return createInferReportRule(
        paramsWithInferFlavor,
        resolver,
        pathResolver,
        analysisRule);
  }

  private static <T extends BuildRule> CxxInferCaptureAndAggregatingRules<T>
  requireTransitiveCaptureAndAggregatingRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      InferBuckConfig inferConfig,
      CxxInferSourceFilter sourceFilter,
      Flavor requiredFlavor,
      Class<T> aggregatingRuleClass) throws NoSuchBuildTargetException {
    BuildRuleParams cleanParams = InferFlavors.paramsWithoutAnyInferFlavor(params);

    ImmutableMap<String, CxxSource> sources = collectSources(
        cleanParams.getBuildTarget(),
        resolver,
        cxxPlatform,
        args);

    ImmutableSet<CxxInferCapture> captureRules = requireInferCaptureBuildRules(
        cleanParams,
        resolver,
        cxxBuckConfig,
        cxxPlatform,
        sources,
        inferConfig,
        sourceFilter,
        args);

    // Build all the transitive dependencies build rules with the Infer's flavor
    ImmutableSet<T> transitiveDepsLibraryRules = requireTransitiveDependentLibraries(
        cxxPlatform,
        cleanParams.getDeps(),
        requiredFlavor,
        aggregatingRuleClass);

    return new CxxInferCaptureAndAggregatingRules<>(
        captureRules,
        transitiveDepsLibraryRules);
  }

  public static CxxInferAnalyze requireInferAnalyzeBuildRuleForCxxDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      InferBuckConfig inferConfig,
      CxxInferSourceFilter sourceFilter) throws NoSuchBuildTargetException {

    Flavor inferAnalyze = InferFlavors.INFER_ANALYZE.get();

    BuildRuleParams paramsWithInferAnalyzeFlavor = InferFlavors
        .paramsWithoutAnyInferFlavor(params)
        .withFlavor(inferAnalyze);

    Optional<CxxInferAnalyze> existingRule = resolver.getRuleOptionalWithType(
        paramsWithInferAnalyzeFlavor.getBuildTarget(), CxxInferAnalyze.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> cxxInferCaptureAndAnalyzeRules =
        requireTransitiveCaptureAndAggregatingRules(
            params,
            resolver,
            cxxBuckConfig,
            cxxPlatform,
            args,
            inferConfig,
            sourceFilter,
            inferAnalyze,
            CxxInferAnalyze.class);

    return createInferAnalyzeRule(
        paramsWithInferAnalyzeFlavor,
        resolver,
        pathResolver,
        inferConfig,
        cxxInferCaptureAndAnalyzeRules);
  }

  public static CxxInferCaptureRulesAggregator
  requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      InferBuckConfig inferConfig,
      CxxInferSourceFilter sourceFilter) throws NoSuchBuildTargetException {

    Flavor inferCaptureOnly = InferFlavors.INFER_CAPTURE_ONLY.get();

    BuildRuleParams paramsWithInferCaptureOnlyFlavor = InferFlavors
        .paramsWithoutAnyInferFlavor(params)
        .withFlavor(inferCaptureOnly);

    Optional<CxxInferCaptureRulesAggregator> existingRule = resolver.getRuleOptionalWithType(
        paramsWithInferCaptureOnlyFlavor.getBuildTarget(), CxxInferCaptureRulesAggregator.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator>
        cxxInferCaptureAndAnalyzeRules = requireTransitiveCaptureAndAggregatingRules(
            params,
            resolver,
            cxxBuckConfig,
            cxxPlatform,
            args,
            inferConfig,
            sourceFilter,
            inferCaptureOnly,
            CxxInferCaptureRulesAggregator.class);

    return createInferCaptureAggregatorRule(
        paramsWithInferCaptureOnlyFlavor,
        resolver,
        pathResolver,
        cxxInferCaptureAndAnalyzeRules);
  }

  private static <T extends BuildRule> ImmutableSet<T> requireTransitiveDependentLibraries(
      final CxxPlatform cxxPlatform,
      final Iterable<? extends BuildRule> deps,
      final Flavor requiredFlavor,
      final Class<T> ruleClass) throws NoSuchBuildTargetException {
    final ImmutableSet.Builder<T> depsBuilder = ImmutableSet.builder();
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule buildRule) throws NoSuchBuildTargetException {
        if (buildRule instanceof CxxLibrary) {
          CxxLibrary library = (CxxLibrary) buildRule;
          depsBuilder.add(
              (ruleClass.cast(library.requireBuildRule(
                  requiredFlavor,
                  cxxPlatform.getFlavor()))));
          return buildRule.getDeps();
        }
        return ImmutableSet.of();
      }
    }.start();
    return depsBuilder.build();
  }

  private static ImmutableList<CxxPreprocessorInput>
  computePreprocessorInputForCxxBinaryDescriptionArg(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.Arg args,
      HeaderSymlinkTree headerSymlinkTree,
      Optional<SymlinkTree> sandboxTree) throws NoSuchBuildTargetException {
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        params,
        cxxPlatform,
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        args.frameworks,
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform,
            FluentIterable.from(params.getDeps())
                .filter(CxxPreprocessorDep.class::isInstance)),
        args.includeDirs,
        sandboxTree);
  }

  private static ImmutableList<CxxPreprocessorInput>
  computePreprocessorInputForCxxLibraryDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.Arg args,
      HeaderSymlinkTree headerSymlinkTree,
      ImmutableList<String> includeDirs,
      Optional<SymlinkTree> sandboxTree) throws NoSuchBuildTargetException {
    boolean shouldCreatePublicHeadersSymlinks = args.xcodePublicHeadersSymlinks.orElse(true);
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        params,
        cxxPlatform,
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        ImmutableSet.of(),
        CxxLibraryDescription.getTransitiveCxxPreprocessorInput(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                args.exportedPreprocessorFlags,
                args.exportedPlatformPreprocessorFlags,
                args.exportedLangPreprocessorFlags,
                cxxPlatform),
            CxxDescriptionEnhancer.parseExportedHeaders(
                params.getBuildTarget(),
                pathResolver,
                Optional.of(cxxPlatform),
                args),
            args.frameworks,
            shouldCreatePublicHeadersSymlinks),
        includeDirs,
        sandboxTree);
  }

  private static ImmutableSet<CxxInferCapture> requireInferCaptureBuildRules(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> sources,
      InferBuckConfig inferBuckConfig,
      CxxInferSourceFilter sourceFilter,
      CxxConstructorArg args) throws NoSuchBuildTargetException {

    InferFlavors.checkNoInferFlavors(params.getBuildTarget().getFlavors());

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableMap<Path, SourcePath> headers = CxxDescriptionEnhancer.parseHeaders(
        params.getBuildTarget(),
        pathResolver,
        Optional.of(cxxPlatform),
        args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.

    boolean shouldCreateHeadersSymlinks = true;
    if (args instanceof CxxLibraryDescription.Arg) {
      shouldCreateHeadersSymlinks =
          ((CxxLibraryDescription.Arg) args).xcodePrivateHeadersSymlinks.orElse(true);
    }
    HeaderSymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.requireHeaderSymlinkTree(
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        headers,
        HeaderVisibility.PRIVATE,
        shouldCreateHeadersSymlinks);
    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree =
          CxxDescriptionEnhancer.createSandboxTree(
              params,
              resolver,
              cxxPlatform);
    }

    ImmutableList<CxxPreprocessorInput> preprocessorInputs;

    if (args instanceof CxxBinaryDescription.Arg) {
      preprocessorInputs = computePreprocessorInputForCxxBinaryDescriptionArg(
          params,
          cxxPlatform,
          (CxxBinaryDescription.Arg) args,
          headerSymlinkTree,
          sandboxTree);
    } else if (args instanceof CxxLibraryDescription.Arg) {
      preprocessorInputs = computePreprocessorInputForCxxLibraryDescriptionArg(
          params,
          resolver,
          pathResolver,
          cxxPlatform,
          (CxxLibraryDescription.Arg) args,
          headerSymlinkTree,
          args.includeDirs,
          sandboxTree);
    } else {
      throw new IllegalStateException("Only Binary and Library args supported.");
    }

    CxxSourceRuleFactory factory = CxxSourceRuleFactory.of(
        params,
        resolver,
        pathResolver,
        cxxBuckConfig,
        cxxPlatform,
        preprocessorInputs,
        CxxFlags.getLanguageFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            args.langCompilerFlags,
            cxxPlatform),
        args.prefixHeader,
        CxxSourceRuleFactory.PicType.PDC,
        sandboxTree);
    return factory.requireInferCaptureBuildRules(
        sources,
        inferBuckConfig,
        sourceFilter);
  }

  private static CxxInferAnalyze createInferAnalyzeRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      InferBuckConfig inferConfig,
      CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> captureAnalyzeRules) {
    return resolver.addToIndex(
        new CxxInferAnalyze(
            params.copyWithChanges(
                params.getBuildTarget(),
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(captureAnalyzeRules.captureRules)
                        .addAll(captureAnalyzeRules.aggregatingRules)
                        .build()),
                params.getExtraDeps()),
            pathResolver,
            inferConfig,
            captureAnalyzeRules));
  }

  private static CxxInferCaptureRulesAggregator createInferCaptureAggregatorRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator> captureAggregatorRules) {
    return resolver.addToIndex(
        new CxxInferCaptureRulesAggregator(
            params,
            pathResolver,
            captureAggregatorRules));
  }

  private static CxxInferComputeReport createInferReportRule(
      BuildRuleParams buildRuleParams,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      CxxInferAnalyze analysisToReport) {
    return buildRuleResolver.addToIndex(
        new CxxInferComputeReport(
            buildRuleParams.copyWithDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(analysisToReport.getTransitiveAnalyzeRules())
                        .add(analysisToReport)
                        .build()),
                buildRuleParams.getExtraDeps()),
            sourcePathResolver,
            analysisToReport));
  }
}
