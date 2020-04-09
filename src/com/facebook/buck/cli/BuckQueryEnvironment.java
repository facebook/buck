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

import com.facebook.buck.cli.OwnersReport.Builder;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.model.tc.factory.TargetConfigurationFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.query.AllPathsFunction;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.AttrRegexFilterFunction;
import com.facebook.buck.query.BuildFileFunction;
import com.facebook.buck.query.ConfigFunction;
import com.facebook.buck.query.DepsFunction;
import com.facebook.buck.query.FilterFunction;
import com.facebook.buck.query.InputsFunction;
import com.facebook.buck.query.KindFunction;
import com.facebook.buck.query.LabelsFunction;
import com.facebook.buck.query.NoopQueryEvaluator;
import com.facebook.buck.query.OwnerFunction;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.query.RdepsFunction;
import com.facebook.buck.query.TestsOfFunction;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.query.QueryTargetAccessor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 *
 * <p>The query language is documented at docs/command/query.soy
 */
public class BuckQueryEnvironment implements QueryEnvironment<QueryTarget> {

  /** List of the default query functions. */
  private static final List<QueryFunction<QueryTarget>> QUERY_FUNCTIONS =
      ImmutableList.of(
          new AllPathsFunction<>(),
          new AttrFilterFunction<>(),
          new AttrRegexFilterFunction<>(),
          new BuildFileFunction<>(),
          new ConfigFunction<>(),
          new DepsFunction<>(),
          new DepsFunction.FirstOrderDepsFunction<>(),
          new DepsFunction.LookupFunction<>(),
          new InputsFunction<>(),
          new FilterFunction<>(),
          new KindFunction<>(),
          new LabelsFunction<>(),
          new OwnerFunction<>(),
          new RdepsFunction<>(),
          new TestsOfFunction<>());

  private final TargetUniverse targetUniverse;
  private final Cell rootCell;
  private final OwnersReport.Builder ownersReportBuilder;
  private final TargetConfigurationFactory targetConfigurationFactory;
  private final TargetPatternEvaluator targetPatternEvaluator;
  private final BuckEventBus eventBus;
  private final QueryEnvironment.TargetEvaluator<QueryTarget> queryTargetEvaluator;
  private final TypeCoercerFactory typeCoercerFactory;

  private final ImmutableMap<Cell, BuildFileTree> buildFileTrees;
  private final Map<BuildTarget, QueryBuildTarget> buildTargetToQueryTarget = new HashMap<>();

  @VisibleForTesting
  protected BuckQueryEnvironment(
      TargetUniverse targetUniverse,
      Cell rootCell,
      Builder ownersReportBuilder,
      TargetConfigurationFactory targetConfigurationFactory,
      TargetPatternEvaluator targetPatternEvaluator,
      BuckEventBus eventBus,
      TypeCoercerFactory typeCoercerFactory) {
    this.targetUniverse = targetUniverse;
    this.eventBus = eventBus;
    this.targetConfigurationFactory = targetConfigurationFactory;
    this.rootCell = rootCell;
    this.ownersReportBuilder = ownersReportBuilder;
    this.buildFileTrees =
        rootCell.getAllCells().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    Function.identity(),
                    cell ->
                        new FilesystemBackedBuildFileTree(
                            cell.getFilesystem(),
                            cell.getBuckConfigView(ParserConfig.class).getBuildFileName())));
    this.targetPatternEvaluator = targetPatternEvaluator;
    this.queryTargetEvaluator = new TargetEvaluator(targetPatternEvaluator);
    this.typeCoercerFactory = typeCoercerFactory;
  }

  public static BuckQueryEnvironment from(
      TargetUniverse targetUniverse,
      Cell rootCell,
      OwnersReport.Builder ownersReportBuilder,
      TargetConfigurationFactory targetConfigurationFactory,
      TargetPatternEvaluator targetPatternEvaluator,
      BuckEventBus eventBus,
      TypeCoercerFactory typeCoercerFactory) {
    return new BuckQueryEnvironment(
        targetUniverse,
        rootCell,
        ownersReportBuilder,
        targetConfigurationFactory,
        targetPatternEvaluator,
        eventBus,
        typeCoercerFactory);
  }

  public static BuckQueryEnvironment from(
      CommandRunnerParams params, TargetUniverse targetUniverse, ParsingContext parsingContext) {
    return from(
        targetUniverse,
        params.getCells().getRootCell(),
        OwnersReport.builder(
            targetUniverse,
            params.getCells().getRootCell(),
            params.getClientWorkingDir(),
            params.getTargetConfiguration()),
        params.getTargetConfigurationFactory(),
        new TargetPatternEvaluator(
            targetUniverse,
            params.getCells().getRootCell(),
            params.getClientWorkingDir(),
            params.getBuckConfig(),
            // We disable mapping //path/to:lib to //path/to:lib#default,static
            // because the query engine doesn't handle flavors very well.
            parsingContext.withApplyDefaultFlavorsMode(
                ParserConfig.ApplyDefaultFlavorsMode.DISABLED),
            params.getTargetConfiguration()),
        params.getBuckEventBus(),
        params.getTypeCoercerFactory());
  }

  public TargetUniverse getTargetUniverse() {
    return targetUniverse;
  }

  public void preloadTargetPatterns(Iterable<String> patterns)
      throws QueryException, InterruptedException {
    try {
      targetPatternEvaluator.preloadTargetPatterns(patterns);
    } catch (IOException e) {
      throw new QueryException(
          e, "Error in preloading targets. %s: %s", e.getClass(), e.getMessage());
    } catch (BuildFileParseException e) {
      throw new QueryException(e, "Error in preloading targets. %s", e.getMessage());
    }
  }

  /**
   * Evaluate the specified query expression in this environment.
   *
   * @return the resulting set of targets.
   * @throws QueryException if the evaluation failed.
   */
  public Set<QueryTarget> evaluateQuery(QueryExpression<QueryTarget> expr)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    preloadTargetPatterns(targetLiterals);
    return new NoopQueryEvaluator<QueryTarget>().eval(expr, this);
  }

  public Set<QueryTarget> evaluateQuery(String query) throws QueryException, InterruptedException {
    return evaluateQuery(QueryExpression.parse(query, this.getQueryParserEnv()));
  }

  private QueryBuildTarget getOrCreateQueryBuildTarget(BuildTarget buildTarget) {
    return buildTargetToQueryTarget.computeIfAbsent(buildTarget, QueryBuildTarget::of);
  }

  public ImmutableSet<QueryBuildTarget> getTargetsFromTargetNodes(
      Iterable<TargetNode<?>> targetNodes) {
    ImmutableSortedSet.Builder<QueryBuildTarget> builder =
        new ImmutableSortedSet.Builder<>(QueryTarget::compare);
    for (TargetNode<?> targetNode : targetNodes) {
      builder.add(getOrCreateQueryBuildTarget(targetNode.getBuildTarget()));
    }
    return builder.build();
  }

  public ImmutableSet<QueryBuildTarget> getTargetsFromBuildTargets(
      Iterable<BuildTarget> buildTargets) {
    ImmutableSortedSet.Builder<QueryBuildTarget> builder =
        new ImmutableSortedSet.Builder<>(QueryTarget::compare);
    for (BuildTarget buildTarget : buildTargets) {
      builder.add(getOrCreateQueryBuildTarget(buildTarget));
    }
    return builder.build();
  }

  public ImmutableSet<TargetNode<?>> getNodesFromQueryTargets(Collection<QueryBuildTarget> input)
      throws QueryException {
    ImmutableSet.Builder<TargetNode<?>> builder =
        ImmutableSet.builderWithExpectedSize(input.size());
    for (QueryBuildTarget target : input) {
      builder.add(targetUniverse.getNode(target.getBuildTarget()));
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets) throws QueryException {
    return allBuildTargets(targets).stream()
        .map(QueryBuildTarget::getBuildTarget)
        .flatMap(target -> targetUniverse.getAllTargetsFromOutgoingEdgesOf(target).stream())
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Set<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets) throws QueryException {
    return allBuildTargets(targets).stream()
        .map(QueryBuildTarget::getBuildTarget)
        .flatMap(target -> targetUniverse.getAllTargetsFromIncomingEdgesOf(target).stream())
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Set<QueryTarget> getInputs(QueryTarget target) throws QueryException {
    // TODO: Give an error message if this cast fails.
    QueryBuildTarget queryBuildTarget = (QueryBuildTarget) target;
    TargetNode<?> node = targetUniverse.getNode(queryBuildTarget.getBuildTarget());
    BuildTarget buildTarget = queryBuildTarget.getBuildTarget();
    Cell cell = rootCell.getCell(buildTarget.getCell());
    return node.getInputs().stream()
        .map(
            path ->
                PathSourcePath.of(
                    cell.getFilesystem(),
                    MorePaths.relativize(
                        rootCell.getFilesystem().getRootPath(),
                        AbsPath.of(cell.getFilesystem().resolve(path)))))
        .map(QueryFileTarget::of)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ImmutableSet<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets)
      throws QueryException {
    ImmutableSet<BuildTarget> buildTargets =
        allBuildTargets(targets).stream()
            .map(QueryBuildTarget::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<BuildTarget> transitiveClosure = targetUniverse.getTransitiveClosure(buildTargets);
    return transitiveClosure.stream()
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public void buildTransitiveClosure(Set<QueryTarget> targets, int maxDepth) throws QueryException {
    // Filter QueryTargets that are build targets and not yet present in the build target graph.
    ImmutableSet<BuildTarget> buildTargets =
        targets.stream()
            .filter(target -> target instanceof QueryBuildTarget)
            .map(target -> ((QueryBuildTarget) target).getBuildTarget())
            .collect(ImmutableSet.toImmutableSet());

    targetUniverse.buildTransitiveClosure(buildTargets);
  }

  @Override
  public ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target) throws QueryException {
    BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
    return ImmutableSet.copyOf(
        getTargetsFromBuildTargets(
            TargetNodes.getTestTargetsForNode(targetUniverse.getNode(buildTarget))));
  }

  // TODO: This should be moved closer to QueryTarget itself, not a helper on BuckQueryEnvironment.
  private ImmutableSet<QueryBuildTarget> allBuildTargets(Iterable<QueryTarget> targets) {
    ImmutableSet.Builder<QueryBuildTarget> builder = ImmutableSet.builder();
    for (QueryTarget target : targets) {
      if (target instanceof QueryBuildTarget) {
        builder.add((QueryBuildTarget) target);
      }
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<QueryTarget> getBuildFiles(Set<QueryTarget> targets) {
    ProjectFilesystem cellFilesystem = rootCell.getFilesystem();
    AbsPath rootPath = cellFilesystem.getRootPath();

    ImmutableSet.Builder<QueryTarget> builder =
        ImmutableSet.builderWithExpectedSize(targets.size());
    for (QueryBuildTarget target : allBuildTargets(targets)) {
      BuildTarget buildTarget = target.getBuildTarget();
      Cell cell = rootCell.getCell(buildTarget.getCell());
      BuildFileTree buildFileTree = Objects.requireNonNull(buildFileTrees.get(cell));
      Optional<ForwardRelativePath> path =
          buildFileTree.getBasePathOfAncestorTarget(
              buildTarget.getCellRelativeBasePath().getPath());
      Preconditions.checkState(path.isPresent());

      RelPath buildFilePath =
          MorePaths.relativize(
              rootPath,
              AbsPath.of(cell.getFilesystem().resolve(path.get()))
                  .resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName()));
      Preconditions.checkState(cellFilesystem.exists(buildFilePath));
      SourcePath sourcePath = PathSourcePath.of(cell.getFilesystem(), buildFilePath);
      builder.add(QueryFileTarget.of(sourcePath));
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<QueryTarget> getFileOwners(ImmutableList<String> files) {
    OwnersReport report = ownersReportBuilder.build(buildFileTrees, files);
    report
        .getInputsWithNoOwners()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("No owner was found for %s", path)));
    report
        .getNonExistentInputs()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("File %s does not exist", path)));
    report
        .getNonFileInputs()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("%s is not a regular file", path)));
    return ImmutableSet.copyOf(getTargetsFromTargetNodes(report.owners.keySet()));
  }

  @Override
  public ImmutableSet<QueryTarget> getConfiguredTargets(
      Set<QueryTarget> targets, Optional<String> configurationName) throws QueryException {
    ImmutableSet.Builder<QueryTarget> builder = ImmutableSet.builder();
    Optional<TargetConfiguration> explicitlyRequestedConfiguration =
        configurationName.map(targetConfigurationFactory::create);
    for (QueryTarget target : targets) {
      if (!(target instanceof QueryBuildTarget)) {
        continue;
      }
      BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
      // Ideally we would use some form of `orElse` here but that doesn't play well with Throwables
      TargetConfiguration configuration =
          explicitlyRequestedConfiguration.isPresent()
              ? explicitlyRequestedConfiguration.get()
              : defaultTargetConfigurationForTarget(buildTarget);
      BuildTarget reconfiguredBuildTarget =
          buildTarget.getUnconfiguredBuildTarget().configure(configuration);
      builder.add(getOrCreateQueryBuildTarget(reconfiguredBuildTarget));
    }
    return builder.build();
  }

  private TargetConfiguration defaultTargetConfigurationForTarget(BuildTarget target)
      throws QueryException {
    TargetNode<?> node = targetUniverse.getNode(target);
    ConstructorArg arg = node.getConstructorArg();
    if (!(arg instanceof BuildRuleArg)) {
      throw new QueryException("Cannot configure configuration rule (eg `platform()`): " + target);
    }

    BuildRuleArg buildRuleArg = (BuildRuleArg) arg;
    return buildRuleArg
        .getDefaultTargetPlatform()
        .map(RuleBasedTargetConfiguration::of)
        .orElseThrow(() -> new QueryException("No default_target_platform found for " + target));
  }

  @Override
  public String getTargetKind(QueryTarget target) throws QueryException {
    BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
    return targetUniverse.getNode(buildTarget).getRuleType().getName();
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute)
      throws QueryException {
    BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
    return QueryTargetAccessor.getTargetsInAttribute(
        typeCoercerFactory,
        targetUniverse.getNode(buildTarget),
        attribute,
        rootCell.getCellNameResolver());
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target, String attribute, Predicate<Object> predicate) throws QueryException {
    BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
    return QueryTargetAccessor.filterAttributeContents(
        typeCoercerFactory,
        targetUniverse.getNode(buildTarget),
        attribute,
        predicate,
        rootCell.getCellNameResolver());
  }

  @Override
  public Iterable<QueryFunction<QueryTarget>> getFunctions() {
    return QUERY_FUNCTIONS;
  }

  @Override
  public QueryEnvironment.TargetEvaluator<QueryTarget> getTargetEvaluator() {
    return queryTargetEvaluator;
  }

  private static class TargetEvaluator implements QueryEnvironment.TargetEvaluator<QueryTarget> {
    private final TargetPatternEvaluator evaluator;

    private TargetEvaluator(TargetPatternEvaluator evaluator) {
      this.evaluator = evaluator;
    }

    @Override
    public ImmutableSet<QueryTarget> evaluateTarget(String target) throws QueryException {
      try {
        return ImmutableSet.copyOf(
            Iterables.concat(evaluator.resolveTargetPatterns(ImmutableList.of(target)).values()));
      } catch (BuildFileParseException | InterruptedException | IOException e) {
        throw new QueryException(e, "Error in resolving targets matching %s", target);
      }
    }

    @Override
    public Type getType() {
      return Type.LAZY;
    }
  }
}
