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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableExceptions;
import com.facebook.buck.core.model.AbstractRuleType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
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
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Asynchronous loader/converter of raw target nodes to configured target nodes */
public class RawTargetNodeToTargetNodeParsePipeline implements AutoCloseable {

  private static final Logger LOG = Logger.get(RawTargetNodeToTargetNodeParsePipeline.class);

  protected final ListeningExecutorService executorService;
  private final boolean speculativeDepsTraversal;
  private final RawTargetNodePipeline rawTargetNodePipeline;
  private final ParserTargetNodeFromRawTargetNodeFactory rawTargetNodeToTargetNodeFactory;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory;
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private final BuckEventBus eventBus;
  private final PipelineNodeCache<BuildTarget, TargetNode<?>> cache;
  private final ConcurrentHashMap<
          Pair<Path, TargetConfiguration>, ListenableFuture<ImmutableList<TargetNode<?>>>>
      allNodeCache = new ConcurrentHashMap<>();
  private final Scope perfEventScope;
  private final PerfEventId perfEventId;
  /**
   * minimum duration time for performance events to be logged (for use with {@link
   * SimplePerfEvent}s). This is on the base class to make it simpler to enable verbose tracing for
   * all of the parsing pipelines.
   */
  private final long minimumPerfEventTimeMs;

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
    this.executorService = executorService;
    this.rawTargetNodePipeline = rawTargetNodePipeline;
    this.speculativeDepsTraversal = speculativeDepsTraversal;
    this.rawTargetNodeToTargetNodeFactory = rawTargetNodeToTargetNodeFactory;
    this.minimumPerfEventTimeMs = LOG.isVerboseEnabled() ? 0 : 10;
    this.perfEventScope = SimplePerfEvent.scope(eventBus, PerfEventId.of(pipelineName));
    this.perfEventId = PerfEventId.of("GetTargetNode");
    this.eventBus = eventBus;
    this.cache =
        new PipelineNodeCache<>(
            cache, RawTargetNodeToTargetNodeParsePipeline::targetNodeIsConfiguration);
    this.unconfiguredBuildTargetViewFactory = unconfiguredBuildTargetViewFactory;
  }

  private static boolean targetNodeIsConfiguration(TargetNode<?> targetNode) {
    return targetNode.getRuleType().getKind() == AbstractRuleType.Kind.CONFIGURATION;
  }

  @SuppressWarnings("CheckReturnValue") // submit result is not used
  private TargetNode<?> computeNodeInScope(
      Cell cell,
      BuildTarget buildTarget,
      RawTargetNode rawNode,
      Function<PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException {
    TargetNode<?> targetNode =
        rawTargetNodeToTargetNodeFactory.createTargetNode(
            cell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(cell, buildTarget.getUnconfiguredBuildTargetView()),
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

  private ListenableFuture<TargetNode<?>> dispatchComputeNode(
      Cell cell, BuildTarget buildTarget, RawTargetNode from) throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }
    return Futures.immediateFuture(computeNode(cell, buildTarget, from));
  }

  private TargetNode<?> computeNode(Cell cell, BuildTarget buildTarget, RawTargetNode from) {
    try (Scope scope =
        SimplePerfEvent.scopeIgnoringShortEvents(
            eventBus,
            perfEventId,
            "target",
            buildTarget,
            perfEventScope,
            minimumPerfEventTimeMs,
            TimeUnit.MILLISECONDS)) {
      Function<PerfEventId, Scope> perfEventScopeFunction =
          perfEventId1 ->
              SimplePerfEvent.scopeIgnoringShortEvents(
                  eventBus, perfEventId1, scope, minimumPerfEventTimeMs, TimeUnit.MILLISECONDS);

      return computeNodeInScope(cell, buildTarget, from, perfEventScopeFunction);
    }
  }

  /**
   * Get or load a target node from a build file configuring it with global platform configuration
   * or {@code default_target_platform} rule arg
   */
  ListenableFuture<TargetNode<?>> getRequestedTargetNodeJob(
      Cell cell,
      UnconfiguredBuildTargetView unconfiguredTarget,
      TargetConfiguration globalTargetConfiguration) {
    ListenableFuture<RawTargetNode> rawTargetNodeFuture =
        rawTargetNodePipeline.getNodeJob(cell, unconfiguredTarget);
    return Futures.transformAsync(
        rawTargetNodeFuture,
        rawTargetNode ->
            configureRequestedTarget(
                cell, unconfiguredTarget, globalTargetConfiguration, rawTargetNode),
        executorService);
  }

  /**
   * Get or load all target nodes from a build file configuring it with global platform
   * configuration or {@code default_target_platform} rule arg
   */
  ListenableFuture<ImmutableList<TargetNode<?>>> getAllRequestedTargetNodesJob(
      Cell cell, Path buildFile, TargetConfiguration globalTargetConfiguration) {
    SettableFuture<ImmutableList<TargetNode<?>>> future = SettableFuture.create();
    Pair<Path, TargetConfiguration> pathCacheKey = new Pair<>(buildFile, globalTargetConfiguration);
    ListenableFuture<ImmutableList<TargetNode<?>>> cachedFuture =
        allNodeCache.putIfAbsent(pathCacheKey, future);

    if (cachedFuture != null) {
      return cachedFuture;
    }

    try {
      ListenableFuture<List<TargetNode<?>>> allNodesListJob =
          Futures.transformAsync(
              rawTargetNodePipeline.getAllNodesJob(cell, buildFile),
              allToConvert -> {
                if (shuttingDown()) {
                  return Futures.immediateCancelledFuture();
                }

                ImmutableList.Builder<ListenableFuture<TargetNode<?>>> allNodeJobs =
                    ImmutableList.builderWithExpectedSize(allToConvert.size());

                for (RawTargetNode from : allToConvert) {
                  UnconfiguredBuildTargetView unconfiguredTarget =
                      ImmutableUnconfiguredBuildTargetView.of(
                          cell.getRoot(), from.getBuildTarget());
                  ListenableFuture<TargetNode<?>> targetNode =
                      configureRequestedTarget(
                          cell, unconfiguredTarget, globalTargetConfiguration, from);
                  allNodeJobs.add(targetNode);
                }

                return Futures.allAsList(allNodeJobs.build());
              },
              executorService);
      future.setFuture(Futures.transform(allNodesListJob, ImmutableList::copyOf, executorService));
    } catch (Throwable t) {
      future.setException(t);
    }
    return future;
  }

  /**
   * Obtain all {@link TargetNode}s from a build file. This may block if the file is not cached.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @param globalTargetConfiguration global target platform
   * @return all targets from the file
   * @throws BuildFileParseException for syntax errors.
   */
  ImmutableList<TargetNode<?>> getAllRequestedTargetNodes(
      Cell cell, Path buildFile, TargetConfiguration globalTargetConfiguration) {
    Preconditions.checkState(!shuttingDown.get());

    try {
      return getAllRequestedTargetNodesJob(cell, buildFile, globalTargetConfiguration).get();
    } catch (Exception e) {
      throw handleFutureGetException(e);
    }
  }

  /**
   * Use {@code default_target_platform} to configure target. Note we use default target platform
   * only for targets explicitly requested by user, but not to dependencies of them hence the method
   * name.
   */
  private ListenableFuture<TargetNode<?>> configureRequestedTarget(
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
    return getNodeJobWithRawNode(cell, configuredTarget, Optional.of(rawTargetNode));
  }

  /** Get build target by name, load if necessary */
  public ListenableFuture<TargetNode<?>> getNodeJob(Cell cell, BuildTarget buildTarget)
      throws BuildTargetException {
    return getNodeJobWithRawNode(cell, buildTarget, Optional.empty());
  }

  private ListenableFuture<TargetNode<?>> getNodeJobWithRawNode(
      Cell cell, BuildTarget buildTarget, Optional<RawTargetNode> rawNodeIfKnown)
      throws BuildTargetException {
    return cache.getJobWithCacheLookup(
        cell,
        buildTarget,
        () -> {
          if (rawNodeIfKnown.isPresent()) {
            return Futures.submitAsync(
                () -> dispatchComputeNode(cell, buildTarget, rawNodeIfKnown.get()),
                executorService);
          } else {
            return Futures.transformAsync(
                rawTargetNodePipeline.getNodeJob(
                    cell, buildTarget.getUnconfiguredBuildTargetView()),
                from -> dispatchComputeNode(cell, buildTarget, from),
                executorService);
          }
        },
        eventBus);
  }

  /**
   * Obtain a {@link TargetNode}. This may block if the node is not cached.
   *
   * @param cell the {@link Cell} that the {@link BuildTarget} belongs to.
   * @param buildTarget name of the node we're looking for. The build file path is derived from it.
   * @return the node
   * @throws BuildFileParseException for syntax errors in the build file.
   * @throws BuildTargetException if the buildTarget is malformed
   */
  public TargetNode<?> getNode(Cell cell, BuildTarget buildTarget)
      throws BuildFileParseException, BuildTargetException {
    Preconditions.checkState(!shuttingDown.get());

    try {
      return getNodeJob(cell, buildTarget).get();
    } catch (Exception e) {
      throw handleFutureGetException(e);
    }
  }

  private static RuntimeException handleFutureGetException(Exception e) {
    if (e instanceof ExecutionException) {
      HumanReadableExceptions.throwIfHumanReadableUnchecked(e.getCause());
    }
    HumanReadableExceptions.throwIfHumanReadableUnchecked(e);
    throw new RuntimeException(e);
  }

  @Override
  public void close() {
    perfEventScope.close();
    shuttingDown.set(true);

    // At this point external callers should not schedule more work, internally job creation
    // should also stop. Any scheduled futures should eventually cancel themselves (all of the
    // AsyncFunctions that interact with the Cache are wired to early-out if `shuttingDown` is
    // true).
    // We could block here waiting for all ongoing work to complete, however the user has already
    // gotten everything they want out of the pipeline, so the only interesting thing that could
    // happen here are exceptions thrown by the ProjectBuildFileParser as its shutting down. These
    // aren't critical enough to warrant bringing down the entire process, as they don't affect the
    // state that has already been extracted from the parser.
  }

  private boolean shuttingDown() {
    return shuttingDown.get();
  }
}
