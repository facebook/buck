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
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;

public final class CxxInferEnhancer {

  private CxxInferEnhancer() {}

  public static final Flavor INFER = ImmutableFlavor.of("infer");
  public static final Flavor INFER_ANALYZE = ImmutableFlavor.of("infer-analyze");

  static class CxxInferCaptureAndAnalyzeRules {
    final ImmutableSet<CxxInferCapture> captureRules;
    final ImmutableSet<CxxInferAnalyze> allAnalyzeRules;

    CxxInferCaptureAndAnalyzeRules(
        ImmutableSet<CxxInferCapture> captureRules,
        ImmutableSet<CxxInferAnalyze> allAnalyzeRules) {
      this.captureRules = captureRules;
      this.allAnalyzeRules = allAnalyzeRules;
    }
  }

  public static BuildTarget createInferAnalyzeBuildTarget(BuildTarget target) {
    return BuildTarget
        .builder(target)
        .addFlavors(
            ImmutableFlavor.of(INFER_ANALYZE.toString()))
        .build();
  }


  public static CxxInferReport requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      CxxInferTools inferTools,
      CxxInferSourceFilter sourceFilter) throws NoSuchBuildTargetException {
    Optional<CxxInferReport> existingRule = resolver.getRuleOptionalWithType(
        params.getBuildTarget(), CxxInferReport.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    BuildRuleParams paramsWithoutInferFlavorWithInferAnalyzeFlavor = params
        .withoutFlavor(INFER)
        .withFlavor(INFER_ANALYZE);

    CxxInferAnalyze analysisRule = requireInferAnalyzeBuildRuleForCxxDescriptionArg(
        paramsWithoutInferFlavorWithInferAnalyzeFlavor,
        resolver,
        pathResolver,
        cxxPlatform,
        args,
        inferTools,
        sourceFilter);
    return createInferReportRule(
        params,
        resolver,
        pathResolver,
        analysisRule);
  }

  public static CxxInferAnalyze requireInferAnalyzeBuildRuleForCxxDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      CxxInferTools inferTools,
      CxxInferSourceFilter sourceFilter) throws NoSuchBuildTargetException {

    BuildTarget inferAnalyzeBuildTarget = createInferAnalyzeBuildTarget(params.getBuildTarget());

    Optional<CxxInferAnalyze> existingRule = resolver.getRuleOptionalWithType(
        inferAnalyzeBuildTarget, CxxInferAnalyze.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    BuildRuleParams paramsWithoutInferFlavor = params.withoutFlavor(INFER_ANALYZE);

    ImmutableMap<Path, SourcePath> headers = CxxDescriptionEnhancer.parseHeaders(
        paramsWithoutInferFlavor.getBuildTarget(),
        pathResolver,
        Optional.of(cxxPlatform),
        args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.requireHeaderSymlinkTree(
        paramsWithoutInferFlavor,
        resolver,
        pathResolver,
        cxxPlatform,
        headers,
        HeaderVisibility.PRIVATE);

    ImmutableList<CxxPreprocessorInput> preprocessorInputs;

    if (args instanceof CxxBinaryDescription.Arg) {
      preprocessorInputs = computePreprocessorInputForCxxBinaryDescriptionArg(
          paramsWithoutInferFlavor,
          cxxPlatform,
          (CxxBinaryDescription.Arg) args,
          headerSymlinkTree);
    } else if (args instanceof CxxLibraryDescription.Arg) {
      preprocessorInputs = computePreprocessorInputForCxxLibraryDescriptionArg(
          paramsWithoutInferFlavor,
          resolver,
          pathResolver,
          cxxPlatform,
          (CxxLibraryDescription.Arg) args,
          headerSymlinkTree);
    } else {
      throw new IllegalStateException("Only Binary and Library args supported.");
    }

    // Build all the transitive dependencies build rules with the Infer's flavor
    ImmutableSet<CxxInferAnalyze> transitiveDepsLibraryRules = requireTransitiveDependentLibraries(
        cxxPlatform,
        paramsWithoutInferFlavor.getDeps());

    ImmutableMap<String, CxxSource> srcs = CxxDescriptionEnhancer.parseCxxSources(
        paramsWithoutInferFlavor.getBuildTarget(),
        pathResolver,
        cxxPlatform,
        args);

    CxxInferCaptureAndAnalyzeRules cxxInferCaptureAndAnalyzeRules =
        new CxxInferCaptureAndAnalyzeRules(
          createInferCaptureBuildRules(
              paramsWithoutInferFlavor,
              resolver,
              cxxPlatform,
              srcs,
              CxxSourceRuleFactory.PicType.PDC,
              inferTools,
              preprocessorInputs,
              CxxFlags.getFlags(
                  args.compilerFlags,
                  args.platformCompilerFlags,
                  cxxPlatform),
              args.prefixHeader,
              sourceFilter),
            transitiveDepsLibraryRules);

    return createInferAnalyzeRule(
        params,
        resolver,
        inferAnalyzeBuildTarget,
        pathResolver,
        inferTools,
        cxxInferCaptureAndAnalyzeRules);
  }

  private static ImmutableSet<CxxInferAnalyze> requireTransitiveDependentLibraries(
      final CxxPlatform cxxPlatform,
      final Iterable<? extends BuildRule> deps) throws NoSuchBuildTargetException {
    final ImmutableSet.Builder<CxxInferAnalyze> depsBuilder = ImmutableSet.builder();
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule buildRule) throws NoSuchBuildTargetException {
        if (buildRule instanceof CxxLibrary) {
          CxxLibrary library = (CxxLibrary) buildRule;
          depsBuilder.add(
              (CxxInferAnalyze) library.requireBuildRule(
                  INFER_ANALYZE,
                  cxxPlatform.getFlavor()));
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
      HeaderSymlinkTree headerSymlinkTree) throws NoSuchBuildTargetException {
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        params,
        cxxPlatform,
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()),
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform,
            FluentIterable.from(params.getDeps())
                .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));
  }

  private static ImmutableList<CxxPreprocessorInput>
  computePreprocessorInputForCxxLibraryDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.Arg args,
      HeaderSymlinkTree headerSymlinkTree) throws NoSuchBuildTargetException {
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        params,
        cxxPlatform,
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        ImmutableSet.<FrameworkPath>of(),
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
            args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of())));
  }

  private static ImmutableSet<CxxInferCapture> createInferCaptureBuildRules(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> sources,
      CxxSourceRuleFactory.PicType picType,
      CxxInferTools inferTools,
      ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputs,
      ImmutableList<String> compilerFlags,
      Optional<SourcePath> prefixHeader,
      CxxInferSourceFilter sourceFilter) {

    CxxSourceRuleFactory factory =
        new CxxSourceRuleFactory(
            params,
            resolver,
            new SourcePathResolver(resolver),
            cxxPlatform,
            cxxPreprocessorInputs,
            compilerFlags,
            prefixHeader,
            picType);
    return factory.createInferCaptureBuildRules(
        sources,
        inferTools,
        sourceFilter);
  }

  private static CxxInferAnalyze createInferAnalyzeRule(
      BuildRuleParams buildRuleParams,
      BuildRuleResolver resolver,
      BuildTarget inferAnalyzeBuildTarget,
      SourcePathResolver pathResolver,
      CxxInferTools inferTools,
      CxxInferCaptureAndAnalyzeRules captureAnalyzeRules) {
    return resolver.addToIndex(
        new CxxInferAnalyze(
            buildRuleParams.copyWithChanges(
                inferAnalyzeBuildTarget,
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(captureAnalyzeRules.captureRules)
                        .addAll(captureAnalyzeRules.allAnalyzeRules)
                        .build()),
                buildRuleParams.getExtraDeps()),
            pathResolver,
            inferTools,
            captureAnalyzeRules));
  }

  private static CxxInferReport createInferReportRule(
      BuildRuleParams buildRuleParams,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      CxxInferAnalyze analysisToReport) {
    ImmutableSortedSet<Path> reportsToMergeFromDeps =
        FluentIterable.from(analysisToReport.getTransitiveAnalyzeRules())
            .transform(
                new Function<CxxInferAnalyze, Path>() {
                  @Override
                  public Path apply(CxxInferAnalyze input) {
                    return input.getPathToOutput();
                  }
                }).toSortedSet(Ordering.natural());

    ImmutableSortedSet<Path> reportsToMerge = ImmutableSortedSet.<Path>naturalOrder()
        .addAll(reportsToMergeFromDeps)
        .add(analysisToReport.getPathToOutput())
        .build();


    return buildRuleResolver.addToIndex(
        new CxxInferReport(
            buildRuleParams.copyWithDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(analysisToReport.getTransitiveAnalyzeRules())
                        .add(analysisToReport)
                        .build()),
                buildRuleParams.getExtraDeps()),
            sourcePathResolver,
            reportsToMerge));
  }
}
