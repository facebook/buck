/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser;

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableDefaultTargetConfiguration;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class RawTargetNodeToTargetNodeParsePipeline
    extends ConvertingPipeline<RawTargetNode, TargetNode<?>, BuildTarget> {

  private static final Logger LOG = Logger.get(RawTargetNodeToTargetNodeParsePipeline.class);

  private final boolean speculativeDepsTraversal;
  private final RawTargetNodePipeline rawTargetNodePipeline;
  private final ParserTargetNodeFromRawTargetNodeFactory rawTargetNodeToTargetNodeFactory;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory;

  /** Create new pipeline for parsing Buck files. */
  public RawTargetNodeToTargetNodeParsePipeline(
      Cache<BuildTarget, TargetNode<?>> cache,
      ListeningExecutorService executorService,
      RawTargetNodePipeline rawTargetNodePipeline,
      BuckEventBus eventBus,
      String pipelineName,
      boolean speculativeDepsTraversal,
      ParserTargetNodeFromRawTargetNodeFactory rawTargetNodeToTargetNodeFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory) {
    super(
        executorService,
        cache,
        eventBus,
        SimplePerfEvent.scope(eventBus, PerfEventId.of(pipelineName)),
        PerfEventId.of("GetTargetNode"));
    this.rawTargetNodePipeline = rawTargetNodePipeline;
    this.speculativeDepsTraversal = speculativeDepsTraversal;
    this.rawTargetNodeToTargetNodeFactory = rawTargetNodeToTargetNodeFactory;
    this.unconfiguredBuildTargetViewFactory = unconfiguredBuildTargetViewFactory;
  }

  @Override
  protected BuildTarget getBuildTarget(
      Path root,
      CanonicalCellName cellName,
      Path buildFile,
      TargetConfiguration targetConfiguration,
      RawTargetNode from) {
    return ImmutableUnconfiguredBuildTargetView.of(root, from.getBuildTarget())
        .configure(targetConfiguration);
  }

  @Override
  @SuppressWarnings("CheckReturnValue") // submit result is not used
  protected TargetNode<?> computeNodeInScope(
      Cell cell,
      BuildTarget buildTarget,
      RawTargetNode rawNode,
      Function<PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException {
    TargetNode<?> targetNode =
        rawTargetNodeToTargetNodeFactory.createTargetNode(
            cell,
            targetFilePath(cell, buildTarget.getUnconfiguredBuildTargetView()),
            buildTarget,
            rawNode,
            perfEventScopeFunction);

    if (speculativeDepsTraversal) {
      executorService.submit(
          () -> {
            for (BuildTarget depTarget : targetNode.getParseDeps()) {
              // TODO(T47190884): Figure out how to do this with CanonicalCellName instead.
              Cell depCell = cell.getCellIgnoringVisibilityCheck(depTarget.getCellPath());
              try {
                if (depTarget.isFlavored()) {
                  getNodeJob(depCell, depTarget.withoutFlavors());
                }
                getNodeJob(depCell, depTarget);
              } catch (BuildTargetException e) {
                // No biggie, we'll hit the error again in the non-speculative path.
                LOG.info(e, "Could not schedule speculative parsing for %s", depTarget);
              }
            }
          });
    }
    return targetNode;
  }

  /**
   * Use {@code default_target_platform} to configure target. Note we use default target platform
   * only for targets explicitly requested by user, but not to dependencies of them hence the method
   * name.
   */
  private TargetNode<?> configureRequestedTarget(
      Cell cell,
      UnconfiguredBuildTargetView unconfiguredTarget,
      TargetConfiguration globalTargetConfiguration,
      RawTargetNode rawTargetNode) {
    TargetConfiguration targetConfiguration = globalTargetConfiguration;
    if (globalTargetConfiguration.getConfigurationTargets().isEmpty()) {
      // We use `default_target_platform` only when global platform is not specified
      String defaultTargetPlatform =
          (String)
              rawTargetNode
                  .getAttributes()
                  .get(CommonDescriptionArg.DEFAULT_TARGET_PLATFORM_PARAM_NAME);
      if (defaultTargetPlatform != null && !defaultTargetPlatform.isEmpty()) {
        UnconfiguredBuildTargetView configurationTarget =
            unconfiguredBuildTargetViewFactory.createForBaseName(
                cell.getCellPathResolver(),
                unconfiguredTarget.getBaseName(),
                defaultTargetPlatform);
        targetConfiguration =
            ImmutableDefaultTargetConfiguration.of(
                ConfigurationBuildTargets.convert(configurationTarget));
      }
    }
    BuildTarget configuredTarget = unconfiguredTarget.configure(targetConfiguration);
    return computeNode(cell, configuredTarget, rawTargetNode);
  }

  private static Path targetFilePath(Cell cell, UnconfiguredBuildTargetView target) {
    return cell.getBuckConfigView(ParserConfig.class).getAbsolutePathToBuildFile(cell, target);
  }

  @Override
  protected ListenableFuture<ImmutableList<RawTargetNode>> getItemsToConvert(
      Cell cell, Path buildFile) throws BuildTargetException {
    return rawTargetNodePipeline.getAllNodesJob(cell, buildFile, EmptyTargetConfiguration.INSTANCE);
  }

  @Override
  protected ListenableFuture<RawTargetNode> getItemToConvert(Cell cell, BuildTarget buildTarget)
      throws BuildTargetException {
    return rawTargetNodePipeline.getNodeJob(cell, buildTarget.getUnconfiguredBuildTargetView());
  }

  ListenableFuture<TargetNode<?>> getRequestedTargetNodeJob(
      Cell cell,
      UnconfiguredBuildTargetView unconfiguredTarget,
      TargetConfiguration globalTargetConfiguration) {
    ListenableFuture<RawTargetNode> rawTargetNodeFuture =
        rawTargetNodePipeline.getNodeJob(cell, unconfiguredTarget);
    return Futures.transform(
        rawTargetNodeFuture,
        rawTargetNode ->
            configureRequestedTarget(
                cell, unconfiguredTarget, globalTargetConfiguration, rawTargetNode),
        MoreExecutors.directExecutor());
  }

  /**
   * Obtain all {@link TargetNode}s from a build file. This may block if the file is not cached.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @param targetConfiguration the configuration of targets.
   * @return all targets from the file
   * @throws BuildFileParseException for syntax errors.
   */
  public final ImmutableList<TargetNode<?>> getAllNodes(
      Cell cell, Path buildFile, TargetConfiguration targetConfiguration)
      throws BuildFileParseException {
    Preconditions.checkState(!shuttingDown());

    try {
      return getAllNodesJob(cell, buildFile, targetConfiguration).get();
    } catch (Exception e) {
      propagateCauseIfInstanceOf(e, BuildFileParseException.class);
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
      throw new RuntimeException(e);
    }
  }

  ListenableFuture<ImmutableList<TargetNode<?>>> getAllRequestedTargetNodesJob(
      Cell cell, Path buildFile, TargetConfiguration globalTargetConfiguration) {
    ListenableFuture<ImmutableList<RawTargetNode>> rawTargetNodesFuture =
        getItemsToConvert(cell, buildFile);

    return Futures.transform(
        rawTargetNodesFuture,
        rawTargetNodes -> {
          ImmutableList.Builder<TargetNode<?>> targetNodes = ImmutableList.builder();
          for (RawTargetNode rawTargetNode : rawTargetNodes) {
            UnconfiguredBuildTargetView unconfiguredTarget =
                ImmutableUnconfiguredBuildTargetView.of(
                    cell.getRoot(), rawTargetNode.getBuildTarget());
            targetNodes.add(
                configureRequestedTarget(
                    cell, unconfiguredTarget, globalTargetConfiguration, rawTargetNode));
          }
          return targetNodes.build();
        },
        executorService);
  }
}
