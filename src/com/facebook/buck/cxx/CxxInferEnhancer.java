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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
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
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      CxxInferTools inferTools) {
    Optional<CxxInferReport> existingRule = resolver.getRuleOptionalWithType(
        params.getBuildTarget(), CxxInferReport.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    BuildRuleParams paramsWithoutInferFlavorWithInferAnalyzeFlavor = params
        .withoutFlavor(INFER)
        .withFlavor(INFER_ANALYZE);

    CxxInferAnalyze analysisRule = requireInferAnalyzeBuildRuleForCxxDescriptionArg(
        targetGraph,
        paramsWithoutInferFlavorWithInferAnalyzeFlavor,
        resolver,
        pathResolver,
        cxxPlatform,
        args,
        inferTools);
    return createInferReportRule(
        params,
        resolver,
        pathResolver,
        analysisRule);
  }

  public static CxxInferAnalyze requireInferAnalyzeBuildRuleForCxxDescriptionArg(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args,
      CxxInferTools inferTools) {

    BuildTarget inferAnalyzeBuildTarget = createInferAnalyzeBuildTarget(params.getBuildTarget());

    Optional<CxxInferAnalyze> existingRule = resolver.getRuleOptionalWithType(
        inferAnalyzeBuildTarget, CxxInferAnalyze.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    BuildRuleParams paramsWithoutInferFlavor = params.withoutFlavor(INFER_ANALYZE);

    ImmutableMap<Path, SourcePath> headers = CxxDescriptionEnhancer.parseHeaders(
        paramsWithoutInferFlavor,
        resolver,
        cxxPlatform,
        args);
    ImmutableMap<String, SourcePath> lexSrcs = CxxDescriptionEnhancer.parseLexSources(
        paramsWithoutInferFlavor,
        resolver,
        args);
    ImmutableMap<String, SourcePath> yaccSrcs = CxxDescriptionEnhancer.parseYaccSources(
        paramsWithoutInferFlavor,
        resolver,
        args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.requireHeaderSymlinkTree(
        paramsWithoutInferFlavor,
        resolver,
        pathResolver,
        cxxPlatform,
        /* includeLexYaccHeaders */ true,
        lexSrcs,
        yaccSrcs,
        headers,
        HeaderVisibility.PRIVATE);

    ImmutableList<CxxPreprocessorInput> preprocessorInputs;

    if (args instanceof CxxBinaryDescription.Arg) {
      preprocessorInputs = computePreprocessorInputForCxxBinaryDescriptionArg(
          targetGraph,
          paramsWithoutInferFlavor,
          pathResolver,
          cxxPlatform,
          (CxxBinaryDescription.Arg) args,
          headerSymlinkTree);
    } else if (args instanceof CxxLibraryDescription.Arg) {
      preprocessorInputs = computePreprocessorInputForCxxLibraryDescriptionArg(
          targetGraph,
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
        targetGraph,
        cxxPlatform,
        paramsWithoutInferFlavor.getDeps());

    // Setup the rules to run lex/yacc.
    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.requireLexYaccSources(
            paramsWithoutInferFlavor,
            resolver,
            pathResolver,
            cxxPlatform,
            lexSrcs,
            yaccSrcs);

    ImmutableMap<String, CxxSource> srcs = CxxDescriptionEnhancer.parseCxxSources(
        paramsWithoutInferFlavor,
        resolver,
        cxxPlatform,
        args);

    // The complete list of input sources.
    ImmutableMap<String, CxxSource> sources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(srcs)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    CxxInferCaptureAndAnalyzeRules cxxInferCaptureAndAnalyzeRules =
        new CxxInferCaptureAndAnalyzeRules(
          createInferCaptureBuildRules(
              paramsWithoutInferFlavor,
              resolver,
              cxxPlatform,
              sources,
              CxxSourceRuleFactory.PicType.PDC,
              inferTools,
              preprocessorInputs,
              CxxFlags.getFlags(
                  args.compilerFlags,
                  args.platformCompilerFlags,
                  cxxPlatform),
              args.prefixHeader),
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
      final TargetGraph targetGraph,
      final CxxPlatform cxxPlatform,
      final Iterable<? extends BuildRule> deps) {
    final ImmutableSet.Builder<CxxInferAnalyze> depsBuilder = ImmutableSet.builder();

    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(deps) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule buildRule) {
            if (buildRule instanceof CxxLibrary) {
              CxxLibrary library = (CxxLibrary) buildRule;
              depsBuilder.add(
                  (CxxInferAnalyze) library.requireBuildRule(
                      targetGraph,
                      INFER_ANALYZE,
                      cxxPlatform.getFlavor()));
              return buildRule.getDeps();
            }
            return ImmutableSet.of();
          }
        };
    visitor.start();
    return depsBuilder.build();
  }

  private static ImmutableList<CxxPreprocessorInput>
  computePreprocessorInputForCxxBinaryDescriptionArg(
      TargetGraph targetGraph,
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.Arg args,
      HeaderSymlinkTree headerSymlinkTree) {
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        targetGraph,
        params,
        cxxPlatform,
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        CxxDescriptionEnhancer.getFrameworkSearchPaths(
            args.frameworks,
            cxxPlatform,
            pathResolver),
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            targetGraph,
            cxxPlatform,
            FluentIterable.from(params.getDeps())
                .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));
  }

  private static ImmutableList<CxxPreprocessorInput>
  computePreprocessorInputForCxxLibraryDescriptionArg(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.Arg args,
      HeaderSymlinkTree headerSymlinkTree) {
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        targetGraph,
        params,
        cxxPlatform,
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        ImmutableSet.<Path>of(),
        CxxLibraryDescription.getTransitiveCxxPreprocessorInput(
            targetGraph,
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                args.exportedPreprocessorFlags,
                args.exportedPlatformPreprocessorFlags,
                args.exportedLangPreprocessorFlags,
                cxxPlatform),
            CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, cxxPlatform, args),
            CxxDescriptionEnhancer.getFrameworkSearchPaths(
                args.frameworks,
                cxxPlatform,
                pathResolver)));
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
      Optional<SourcePath> prefixHeader) {

    CxxSourceRuleFactory factory =
        new CxxSourceRuleFactory(
            params,
            resolver,
            new SourcePathResolver(resolver),
            cxxPlatform,
            cxxPreprocessorInputs,
            compilerFlags,
            prefixHeader);
    return factory.createInferCaptureBuildRules(
        sources,
        picType,
        inferTools);
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
