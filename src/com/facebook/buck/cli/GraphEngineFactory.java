/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.DefaultCellNameResolverProvider;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.files.DirectoryListComputation;
import com.facebook.buck.core.files.FileTreeComputation;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.composition.ComposedComputation;
import com.facebook.buck.core.graph.transformation.composition.Composition;
import com.facebook.buck.core.graph.transformation.impl.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.platform.impl.EmptyPlatform;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDepsPackage;
import com.facebook.buck.core.parser.BuildPackagePaths;
import com.facebook.buck.core.parser.BuildTargetPatternToBuildPackagePathComputation;
import com.facebook.buck.core.parser.BuildTargetPatternToBuildPackagePathKey;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.parser.BuiltTargetVerifier;
import com.facebook.buck.parser.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.parser.DefaultUnconfiguredTargetNodeFactory;
import com.facebook.buck.parser.NoopPackageBoundaryChecker;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ProjectBuildFileParserFactory;
import com.facebook.buck.parser.UnconfiguredTargetNodeToTargetNodeFactory;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.manifest.BuildPackagePathToBuildFileManifestComputation;
import com.facebook.buck.parser.targetnode.BuildPackagePathToUnconfiguredTargetNodePackageComputation;
import com.facebook.buck.parser.targetnode.BuildPackagePathToUnconfiguredTargetNodePackageKey;
import com.facebook.buck.parser.targetnode.BuildTargetToUnconfiguredTargetNodeComputation;
import com.facebook.buck.parser.targetnode.UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.concat.Concatable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closer;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;

/** Factory that creates {@link GraphTransformationEngine} for given parameters */
public class GraphEngineFactory {

  private GraphEngineFactory() {}

  /**
   * Create new Graph Engine instance. Users should use {@link
   * GraphTransformationEngine#compute(ComputeKey)} or {@link
   * GraphTransformationEngine#computeUnchecked(ComputeKey)} to start a transformation and obtain
   * the result.
   *
   * @param cell Cell for which to create Graph Engine instance. Each cell has to be processed
   *     separately because potentially each cell has its own configuration.
   * @param closer Register closeable resources with this object. User is expected to call {@link
   *     Closer#close()} after computations are done to release resources.
   * @param params All other parameters used to run the command.
   */
  public static GraphTransformationEngine create(
      Cells cells, Cell cell, Closer closer, CommandRunnerParams params) {
    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);

    // COMPUTATION: discover paths of build files needed to be parsed for provided target
    // patterns
    BuildTargetPatternToBuildPackagePathComputation patternToPackagePathComputation =
        BuildTargetPatternToBuildPackagePathComputation.of(
            parserConfig.getBuildFileName(), cell.getFilesystem().asView());

    // -- DEP COMPUTATION: listing of a specific directory to search for build file
    DirectoryListComputation directoryListComputation =
        DirectoryListComputation.of(cell.getFilesystemViewForSourceFiles());

    // -- DEP COMPUTATION: file system tree to traverse to search for build files specified in
    // recursive spec
    FileTreeComputation fileTreeComputation = FileTreeComputation.of();

    // COMPUTATION: parse build file to build file manifest (structured representation of a
    // build file)
    ProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            new DefaultTypeCoercerFactory(),
            params.getConsole(),
            new ParserPythonInterpreterProvider(cell.getBuckConfig(), params.getExecutableFinder()),
            params.getKnownRuleTypesProvider());

    ProjectBuildFileParser buildFileParser =
        projectBuildFileParserFactory.createFileParser(
            params.getBuckEventBus(), params.getCells().getRootCell(), params.getWatchman(), true);

    // Once computation is over, we want to close ProjectBuildFileParser to potentially release
    // resources
    // ProjectBuildFileParser implements AutoCloseable but Guava Closer only works with
    // Closeable, so we create another wrapper
    // TODO(buck_team): implement Closer which works with AutoCloseables
    closer.register(
        () -> {
          try {
            buildFileParser.close();
          } catch (Exception ex) {
            throw new IOException(ex);
          }
        });

    BuildPackagePathToBuildFileManifestComputation packagePathToManifestComputation =
        BuildPackagePathToBuildFileManifestComputation.of(
            buildFileParser,
            cell.getFilesystem().getPath(parserConfig.getBuildFileName()),
            cell.getRoot().getPath(),
            false);

    // COMPOSITION: build target pattern to build file manifest
    ComposedComputation<BuildTargetPatternToBuildPackagePathKey, BuildPackagePaths>
        patternToPathComputation =
            Composition.asComposition(BuildPackagePaths.class, patternToPackagePathComputation);

    // COMPUTATION: Unconfigured build target to raw target node computation
    DefaultUnconfiguredTargetNodeFactory rawTargetNodeFactory =
        new DefaultUnconfiguredTargetNodeFactory(
            params.getKnownRuleTypesProvider(),
            new BuiltTargetVerifier(),
            cells,
            new SelectorListFactory(
                new SelectorFactory(params.getUnconfiguredBuildTargetFactory())),
            params.getTypeCoercerFactory());

    BuildTargetToUnconfiguredTargetNodeComputation buildTargetToUnconfiguredTargetNodeComputation =
        BuildTargetToUnconfiguredTargetNodeComputation.of(rawTargetNodeFactory, cell);

    // COMPUTATION: raw target node to raw target node with deps

    // TODO: replace with TargetPlatformResolver
    TargetPlatformResolver targetPlatformResolver =
        (targetConfiguration, dependencyStack) -> EmptyPlatform.INSTANCE;
    UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory =
        new UnconfiguredTargetNodeToTargetNodeFactory(
            params.getTypeCoercerFactory(),
            params.getKnownRuleTypesProvider(),
            new DefaultConstructorArgMarshaller(),
            new TargetNodeFactory(
                params.getTypeCoercerFactory(),
                new DefaultCellNameResolverProvider(
                    new Cells(cell.getCell(CanonicalCellName.rootCell())))),
            // TODO: replace with ThrowingPackageBoundaryChecker
            new NoopPackageBoundaryChecker(),
            // TODO: replace with symlink checker
            (buildFile, node) -> {},
            // TODO: replace with DefaultSelectorListResolver
            new SelectorListResolver() {
              @Nullable
              @Override
              public <T> T resolveList(
                  SelectableConfigurationContext configurationContext,
                  BuildTarget buildTarget,
                  String attributeName,
                  SelectorList<T> selectorList,
                  Concatable<T> concatable,
                  DependencyStack dependencyStack) {
                return selectorList.getSelectors().get(0).getDefaultConditionValue();
              }
            },
            // TODO: replace with RuleBasedConstraintResolver
            targetPlatformResolver,
            new MultiPlatformTargetConfigurationTransformer(targetPlatformResolver),
            params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE),
            cell.getBuckConfig(),
            Optional.empty());

    UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation
        unconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation =
            UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation.of(
                unconfiguredTargetNodeToTargetNodeFactory, cell);

    // COMPUTATION: path to a package to a list of raw target nodes with deps
    // this computation is workaround because left compositions are very slow
    // TODO: Use right compositions instead
    BuildPackagePathToUnconfiguredTargetNodePackageComputation
        buildPackagePathToUnconfiguredTargetNodePackageComputation =
            BuildPackagePathToUnconfiguredTargetNodePackageComputation.of(
                unconfiguredTargetNodeToTargetNodeFactory, cells, cell, false);

    // COMPOSITION: build target pattern to raw target node package
    ComposedComputation<
            BuildTargetPatternToBuildPackagePathKey, UnconfiguredTargetNodeWithDepsPackage>
        patternToRawTargetNodeWithDepsPackageComputation =
            Composition.composeLeft(
                UnconfiguredTargetNodeWithDepsPackage.class,
                patternToPathComputation,
                (key, result) ->
                    result.getPackageRoots().stream()
                        .map(path -> BuildPackagePathToUnconfiguredTargetNodePackageKey.of(path))
                        .collect(ImmutableSet.toImmutableSet()));

    // ENGINE: bind computations to caches and feed them to Graph Engine

    // TODO: pass caches from global state
    GraphTransformationEngine engine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(
                new GraphComputationStage<>(patternToPackagePathComputation),
                new GraphComputationStage<>(
                    directoryListComputation,
                    params
                        .getGlobalState()
                        .getDirectoryListCaches()
                        .getUnchecked(cell.getRoot().getPath())),
                new GraphComputationStage<>(
                    fileTreeComputation,
                    params
                        .getGlobalState()
                        .getFileTreeCaches()
                        .getUnchecked(cell.getRoot().getPath())),
                patternToPathComputation.asStage(),
                new GraphComputationStage<>(
                    packagePathToManifestComputation,
                    params
                        .getGlobalState()
                        .getBuildFileManifestCaches()
                        .getUnchecked(cell.getRoot().getPath())),
                new GraphComputationStage<>(buildTargetToUnconfiguredTargetNodeComputation),
                new GraphComputationStage<>(
                    unconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation),
                new GraphComputationStage<>(
                    buildPackagePathToUnconfiguredTargetNodePackageComputation),
                patternToRawTargetNodeWithDepsPackageComputation.asStage()),
            16,
            params.getDepsAwareExecutorSupplier().get());

    return engine;
  }
}
