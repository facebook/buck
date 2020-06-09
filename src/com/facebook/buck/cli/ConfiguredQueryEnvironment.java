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
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.query.AllPathsFunction;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.AttrRegexFilterFunction;
import com.facebook.buck.query.BuildFileFunction;
import com.facebook.buck.query.ConfigFunction;
import com.facebook.buck.query.ConfiguredQueryBuildTarget;
import com.facebook.buck.query.ConfiguredQueryTarget;
import com.facebook.buck.query.DepsFunction;
import com.facebook.buck.query.EvaluatingQueryEnvironment;
import com.facebook.buck.query.FilterFunction;
import com.facebook.buck.query.InputsFunction;
import com.facebook.buck.query.KindFunction;
import com.facebook.buck.query.LabelsFunction;
import com.facebook.buck.query.NoopQueryEvaluator;
import com.facebook.buck.query.OwnerFunction;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.RdepsFunction;
import com.facebook.buck.query.TestsOfFunction;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.rules.query.QueryTargetAccessor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * QueryEnvironment implementation that operates over the configured target graph.
 *
 * <p>The query language is documented at docs/command/query.soy
 */
public class ConfiguredQueryEnvironment
    implements EvaluatingQueryEnvironment<ConfiguredQueryTarget> {

  private final TargetUniverse targetUniverse;
  private final Cell rootCell;
  private final OwnersReport.Builder<TargetNode<?>> ownersReportBuilder;
  private final TargetConfigurationFactory targetConfigurationFactory;
  private final TargetPatternEvaluator targetPatternEvaluator;
  private final BuckEventBus eventBus;
  private final QueryEnvironment.TargetEvaluator<ConfiguredQueryTarget> queryTargetEvaluator;
  private final TypeCoercerFactory typeCoercerFactory;

  private final ImmutableMap<Cell, BuildFileTree> buildFileTrees;
  private final Map<BuildTarget, ConfiguredQueryBuildTarget> buildTargetToQueryTarget =
      new HashMap<>();

  @VisibleForTesting
  protected ConfiguredQueryEnvironment(
      TargetUniverse targetUniverse,
      Cell rootCell,
      Builder<TargetNode<?>> ownersReportBuilder,
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

  public static ConfiguredQueryEnvironment from(
      TargetUniverse targetUniverse,
      Cell rootCell,
      OwnersReport.Builder<TargetNode<?>> ownersReportBuilder,
      TargetConfigurationFactory targetConfigurationFactory,
      TargetPatternEvaluator targetPatternEvaluator,
      BuckEventBus eventBus,
      TypeCoercerFactory typeCoercerFactory) {
    return new ConfiguredQueryEnvironment(
        targetUniverse,
        rootCell,
        ownersReportBuilder,
        targetConfigurationFactory,
        targetPatternEvaluator,
        eventBus,
        typeCoercerFactory);
  }

  public static ConfiguredQueryEnvironment from(
      CommandRunnerParams params, TargetUniverse targetUniverse) {
    return from(
        targetUniverse,
        params.getCells().getRootCell(),
        OwnersReport.builderForConfigured(
            params.getCells().getRootCell(),
            params.getClientWorkingDir(),
            targetUniverse,
            params.getTargetConfiguration()),
        params.getTargetConfigurationFactory(),
        new TargetPatternEvaluator(
            targetUniverse,
            params.getCells().getRootCell(),
            params.getClientWorkingDir(),
            params.getBuckConfig(),
            params.getTargetConfiguration()),
        params.getBuckEventBus(),
        params.getTypeCoercerFactory());
  }

  public TargetUniverse getTargetUniverse() {
    return targetUniverse;
  }

  @Override
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

  @Override
  public Set<ConfiguredQueryTarget> evaluateQuery(QueryExpression<ConfiguredQueryTarget> expr)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    preloadTargetPatterns(targetLiterals);
    return new NoopQueryEvaluator<ConfiguredQueryTarget>().eval(expr, this);
  }

  private ConfiguredQueryBuildTarget getOrCreateQueryBuildTarget(BuildTarget buildTarget) {
    return buildTargetToQueryTarget.computeIfAbsent(buildTarget, ConfiguredQueryBuildTarget::of);
  }

  public ImmutableSet<ConfiguredQueryBuildTarget> getTargetsFromTargetNodes(
      Iterable<TargetNode<?>> targetNodes) {
    ImmutableSet.Builder<ConfiguredQueryBuildTarget> builder = ImmutableSet.builder();
    for (TargetNode<?> targetNode : targetNodes) {
      builder.add(getOrCreateQueryBuildTarget(targetNode.getBuildTarget()));
    }
    return builder.build();
  }

  public ImmutableSet<ConfiguredQueryBuildTarget> getTargetsFromBuildTargets(
      Iterable<BuildTarget> buildTargets) {
    ImmutableSet.Builder<ConfiguredQueryBuildTarget> builder = ImmutableSet.builder();
    for (BuildTarget buildTarget : buildTargets) {
      builder.add(getOrCreateQueryBuildTarget(buildTarget));
    }
    return builder.build();
  }

  public ImmutableSet<TargetNode<?>> getNodesFromQueryTargets(
      Collection<ConfiguredQueryBuildTarget> input) throws QueryException {
    ImmutableSet.Builder<TargetNode<?>> builder =
        ImmutableSet.builderWithExpectedSize(input.size());
    for (ConfiguredQueryBuildTarget target : input) {
      targetUniverse.getNode(target.getBuildTarget()).ifPresent(builder::add);
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getFwdDeps(Iterable<ConfiguredQueryTarget> targets)
      throws QueryException {
    return allBuildTargets(targets).stream()
        .map(ConfiguredQueryBuildTarget::getBuildTarget)
        .flatMap(target -> targetUniverse.getAllTargetsFromOutgoingEdgesOf(target).stream())
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Set<ConfiguredQueryTarget> getReverseDeps(Iterable<ConfiguredQueryTarget> targets)
      throws QueryException {
    return allBuildTargets(targets).stream()
        .map(ConfiguredQueryBuildTarget::getBuildTarget)
        .flatMap(target -> targetUniverse.getAllTargetsFromIncomingEdgesOf(target).stream())
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Set<ConfiguredQueryTarget> getInputs(ConfiguredQueryTarget target) throws QueryException {
    // TODO: Give an error message if this cast fails.
    ConfiguredQueryBuildTarget queryBuildTarget = (ConfiguredQueryBuildTarget) target;
    Optional<TargetNode<?>> maybeNode = targetUniverse.getNode(queryBuildTarget.getBuildTarget());
    if (!maybeNode.isPresent()) {
      return ImmutableSet.of();
    }

    TargetNode<?> node = maybeNode.get();
    BuildTarget buildTarget = queryBuildTarget.getBuildTarget();
    Cell cell = rootCell.getCell(buildTarget.getCell());
    return node.getInputs().stream()
        .map(
            path ->
                PathSourcePath.of(
                    cell.getFilesystem(),
                    MorePaths.relativize(
                        rootCell.getFilesystem().getRootPath(),
                        cell.getFilesystem().resolve(path))))
        .map(QueryFileTarget::of)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getTransitiveClosure(
      Set<ConfiguredQueryTarget> targets) throws QueryException {
    ImmutableSet<BuildTarget> buildTargets =
        allBuildTargets(targets).stream()
            .map(ConfiguredQueryBuildTarget::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<BuildTarget> transitiveClosure = targetUniverse.getTransitiveClosure(buildTargets);
    return transitiveClosure.stream()
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public void buildTransitiveClosure(Set<ConfiguredQueryTarget> targets) throws QueryException {
    // Filter QueryTargets that are build targets and not yet present in the build target graph.
    ImmutableSet<BuildTarget> buildTargets =
        targets.stream()
            .filter(target -> target instanceof ConfiguredQueryBuildTarget)
            .map(target -> ((ConfiguredQueryBuildTarget) target).getBuildTarget())
            .collect(ImmutableSet.toImmutableSet());

    targetUniverse.buildTransitiveClosure(buildTargets);
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getTestsForTarget(ConfiguredQueryTarget target)
      throws QueryException {
    BuildTarget buildTarget = ((ConfiguredQueryBuildTarget) target).getBuildTarget();
    Optional<TargetNode<?>> maybeNode = targetUniverse.getNode(buildTarget);
    if (!maybeNode.isPresent()) {
      return ImmutableSet.of();
    }

    return TargetNodes.getTestTargetsForNode(maybeNode.get()).stream()
        .map(targetUniverse::getNode)
        .filter(Optional::isPresent)
        .map(nodeOptional -> getOrCreateQueryBuildTarget(nodeOptional.get().getBuildTarget()))
        .collect(ImmutableSet.toImmutableSet());
  }

  // TODO: This should be moved closer to ConfiguredQueryTarget itself, not a helper on
  // BuckQueryEnvironment.
  private ImmutableSet<ConfiguredQueryBuildTarget> allBuildTargets(
      Iterable<ConfiguredQueryTarget> targets) {
    ImmutableSet.Builder<ConfiguredQueryBuildTarget> builder = ImmutableSet.builder();
    for (ConfiguredQueryTarget target : targets) {
      if (target instanceof ConfiguredQueryBuildTarget) {
        builder.add((ConfiguredQueryBuildTarget) target);
      }
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getBuildFiles(Set<ConfiguredQueryTarget> targets) {
    ProjectFilesystem cellFilesystem = rootCell.getFilesystem();
    AbsPath rootPath = cellFilesystem.getRootPath();

    ImmutableSet.Builder<ConfiguredQueryTarget> builder =
        ImmutableSet.builderWithExpectedSize(targets.size());
    for (ConfiguredQueryBuildTarget target : allBuildTargets(targets)) {
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
              cell.getFilesystem()
                  .resolve(path.get())
                  .resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName()));
      Preconditions.checkState(cellFilesystem.exists(buildFilePath));
      SourcePath sourcePath = PathSourcePath.of(cell.getFilesystem(), buildFilePath);
      builder.add(QueryFileTarget.of(sourcePath));
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getFileOwners(ImmutableList<String> files) {
    OwnersReport<TargetNode<?>> report = ownersReportBuilder.build(buildFileTrees, files);
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
  public ImmutableSet<ConfiguredQueryTarget> getConfiguredTargets(
      Set<ConfiguredQueryTarget> targets, Optional<String> configurationName)
      throws QueryException {
    ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();
    Optional<TargetConfiguration> explicitlyRequestedConfiguration =
        configurationName.map(targetConfigurationFactory::create);
    for (ConfiguredQueryTarget target : targets) {
      if (!(target instanceof ConfiguredQueryBuildTarget)) {
        continue;
      }
      BuildTarget buildTarget = ((ConfiguredQueryBuildTarget) target).getBuildTarget();
      // Ideally we would use some form of `orElse` here but that doesn't play well with Throwables
      TargetConfiguration configuration =
          explicitlyRequestedConfiguration.isPresent()
              ? explicitlyRequestedConfiguration.get()
              : defaultTargetConfigurationForTarget(buildTarget);
      BuildTarget reconfiguredBuildTarget =
          buildTarget.getUnconfiguredBuildTarget().configure(configuration);
      builder.add(reconfiguredBuildTarget);
    }
    // NOTE: We only want to return the newly configured target if that target exists in the
    // universe. Otherwise, we can run into issues down the line when people ask for totally
    // reasonable things like "please print the attributes of this target".
    return builder.build().stream()
        .map(targetUniverse::getNode)
        .filter(Optional::isPresent)
        .map(nodeOptional -> getOrCreateQueryBuildTarget(nodeOptional.get().getBuildTarget()))
        .collect(ImmutableSet.toImmutableSet());
  }

  private TargetConfiguration defaultTargetConfigurationForTarget(BuildTarget target)
      throws QueryException {
    TargetNode<?> node =
        targetUniverse
            .getNode(target)
            .orElseThrow(() -> new QueryException("Unable to find existing node for " + target));
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
  public String getTargetKind(ConfiguredQueryTarget target) throws QueryException {
    BuildTarget buildTarget = ((ConfiguredQueryBuildTarget) target).getBuildTarget();
    return targetUniverse
        .getNode(buildTarget)
        .orElseThrow(() -> new QueryException("Unable to find node for " + buildTarget))
        .getRuleType()
        .getName();
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> getTargetsInAttribute(
      ConfiguredQueryTarget target, ParamName attribute) throws QueryException {
    BuildTarget buildTarget = ((ConfiguredQueryBuildTarget) target).getBuildTarget();
    TargetNode<?> node =
        targetUniverse
            .getNode(buildTarget)
            .orElseThrow(() -> new QueryException("Unable to find node for " + buildTarget));
    return QueryTargetAccessor.getTargetsInAttribute(
        typeCoercerFactory, node, attribute, rootCell.getCellNameResolver());
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      ConfiguredQueryTarget target, ParamName attribute, Predicate<Object> predicate)
      throws QueryException {
    BuildTarget buildTarget = ((ConfiguredQueryBuildTarget) target).getBuildTarget();
    TargetNode<?> node =
        targetUniverse
            .getNode(buildTarget)
            .orElseThrow(() -> new QueryException("Unable to find node for " + buildTarget));
    return QueryTargetAccessor.filterAttributeContents(
        typeCoercerFactory, node, attribute, predicate, rootCell.getCellNameResolver());
  }

  /** The (genericized) set of functions available to this query environment */
  public static <T> Iterable<QueryFunction<T>> defaultFunctions() {
    return ImmutableList.of(
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
  }

  @Override
  public Iterable<QueryFunction<ConfiguredQueryTarget>> getFunctions() {
    return defaultFunctions();
  }

  @Override
  public QueryEnvironment.TargetEvaluator<ConfiguredQueryTarget> getTargetEvaluator() {
    return queryTargetEvaluator;
  }

  private static class TargetEvaluator
      implements QueryEnvironment.TargetEvaluator<ConfiguredQueryTarget> {
    private final TargetPatternEvaluator evaluator;

    private TargetEvaluator(TargetPatternEvaluator evaluator) {
      this.evaluator = evaluator;
    }

    @Override
    public ImmutableSet<ConfiguredQueryTarget> evaluateTarget(String target) throws QueryException {
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
