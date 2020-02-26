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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.HumanReadableExceptions;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.detector.TargetConfigurationDetector;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Asynchronous loader/converter of raw target nodes to configured target nodes */
public class UnconfiguredTargetNodeToTargetNodeParsePipeline implements AutoCloseable {

  private static final Logger LOG =
      Logger.get(UnconfiguredTargetNodeToTargetNodeParsePipeline.class);

  protected final ListeningExecutorService executorService;
  private final boolean speculativeDepsTraversal;
  private final UnconfiguredTargetNodePipeline unconfiguredTargetNodePipeline;
  private final ParserTargetNodeFromUnconfiguredTargetNodeFactory rawTargetNodeToTargetNodeFactory;
  private final TargetConfigurationDetector targetConfigurationDetector;
  private final boolean requireTargetPlatform;
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private final BuckEventBus eventBus;
  private final PipelineNodeCache<BuildTarget, TargetNodeMaybeIncompatible> cache;
  private final ConcurrentHashMap<
          Pair<AbsPath, Optional<TargetConfiguration>>,
          ListenableFuture<ImmutableList<TargetNodeMaybeIncompatible>>>
      allNodeCache = new ConcurrentHashMap<>();
  private final Scope perfEventScope;
  private final SimplePerfEvent.PerfEventId perfEventId;

  /**
   * minimum duration time for performance events to be logged (for use with {@link
   * SimplePerfEvent}s). This is on the base class to make it simpler to enable verbose tracing for
   * all of the parsing pipelines.
   */
  private final long minimumPerfEventTimeMs;

  /** Create new pipeline for parsing Buck files. */
  public UnconfiguredTargetNodeToTargetNodeParsePipeline(
      Cache<BuildTarget, TargetNodeMaybeIncompatible> cache,
      ListeningExecutorService executorService,
      UnconfiguredTargetNodePipeline unconfiguredTargetNodePipeline,
      TargetConfigurationDetector targetConfigurationDetector,
      BuckEventBus eventBus,
      String pipelineName,
      boolean speculativeDepsTraversal,
      ParserTargetNodeFromUnconfiguredTargetNodeFactory rawTargetNodeToTargetNodeFactory,
      boolean requireTargetPlatform) {
    this.executorService = executorService;
    this.unconfiguredTargetNodePipeline = unconfiguredTargetNodePipeline;
    this.targetConfigurationDetector = targetConfigurationDetector;
    this.speculativeDepsTraversal = speculativeDepsTraversal;
    this.rawTargetNodeToTargetNodeFactory = rawTargetNodeToTargetNodeFactory;
    this.requireTargetPlatform = requireTargetPlatform;
    this.minimumPerfEventTimeMs = LOG.isVerboseEnabled() ? 0 : 10;
    this.perfEventScope =
        SimplePerfEvent.scope(eventBus, SimplePerfEvent.PerfEventId.of(pipelineName));
    this.perfEventId = SimplePerfEvent.PerfEventId.of("GetTargetNode");
    this.eventBus = eventBus;
    this.cache =
        new PipelineNodeCache<BuildTarget, TargetNodeMaybeIncompatible>(
            cache, UnconfiguredTargetNodeToTargetNodeParsePipeline::targetNodeIsConfiguration);
  }

  private static boolean targetNodeIsConfiguration(
      TargetNodeMaybeIncompatible targetNodeMaybeIncompatible) {
    Optional<TargetNode<?>> targetNode = targetNodeMaybeIncompatible.getTargetNodeOptional();
    return targetNode.isPresent()
        && targetNode.get().getRuleType().getKind() == RuleType.Kind.CONFIGURATION;
  }

  @SuppressWarnings("CheckReturnValue") // submit result is not used
  private TargetNodeMaybeIncompatible computeNodeInScope(
      Cell cell,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      UnconfiguredTargetNode rawNode,
      Function<SimplePerfEvent.PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException {
    TargetNodeMaybeIncompatible targetNodeMaybeIncompatible =
        rawTargetNodeToTargetNodeFactory.createTargetNode(
            cell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(cell, buildTarget.getUnconfiguredBuildTarget()),
            buildTarget,
            dependencyStack,
            rawNode,
            perfEventScopeFunction);
    Optional<TargetNode<?>> targetNode = targetNodeMaybeIncompatible.getTargetNodeOptional();
    if (!targetNode.isPresent()) {
      return targetNodeMaybeIncompatible;
    }

    if (speculativeDepsTraversal) {
      executorService.submit(
          () -> {
            for (BuildTarget depTarget : targetNode.get().getParseDeps()) {
              Cell depCell = cell.getCell(depTarget.getCell());
              try {
                if (depTarget.isFlavored()) {
                  BuildTarget depTargetWithoutFlavors = depTarget.withoutFlavors();
                  getNodeJob(
                      depCell,
                      depTargetWithoutFlavors,
                      dependencyStack.child(depTargetWithoutFlavors));
                }
                getNodeJob(depCell, depTarget, dependencyStack.child(depTarget));
              } catch (BuildTargetException e) {
                // No biggie, we'll hit the error again in the non-speculative path.
                LOG.info(e, "Could not schedule speculative parsing for %s", depTarget);
              }
            }
          });
    }
    return targetNodeMaybeIncompatible;
  }

  private ListenableFuture<TargetNodeMaybeIncompatible> dispatchComputeNode(
      Cell cell,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      UnconfiguredTargetNode from)
      throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }
    return Futures.immediateFuture(computeNode(cell, buildTarget, dependencyStack, from));
  }

  private TargetNodeMaybeIncompatible computeNode(
      Cell cell,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      UnconfiguredTargetNode from) {
    try (Scope scope =
        SimplePerfEvent.scopeIgnoringShortEvents(
            eventBus,
            perfEventId,
            "target",
            buildTarget,
            perfEventScope,
            minimumPerfEventTimeMs,
            TimeUnit.MILLISECONDS)) {
      Function<SimplePerfEvent.PerfEventId, Scope> perfEventScopeFunction =
          perfEventId1 ->
              SimplePerfEvent.scopeIgnoringShortEvents(
                  eventBus, perfEventId1, scope, minimumPerfEventTimeMs, TimeUnit.MILLISECONDS);

      return computeNodeInScope(cell, buildTarget, dependencyStack, from, perfEventScopeFunction);
    }
  }

  /**
   * Get or load a target node from a build file configuring it with global platform configuration
   * or {@code default_target_platform} rule arg
   */
  ListenableFuture<TargetNodeMaybeIncompatible> getRequestedTargetNodeJob(
      Cell cell,
      UnconfiguredBuildTarget unconfiguredTarget,
      Optional<TargetConfiguration> globalTargetConfiguration) {
    ListenableFuture<UnconfiguredTargetNode> rawTargetNodeFuture =
        unconfiguredTargetNodePipeline.getNodeJob(
            cell, unconfiguredTarget, DependencyStack.top(unconfiguredTarget));
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
  ListenableFuture<ImmutableList<TargetNodeMaybeIncompatible>> getAllRequestedTargetNodesJob(
      Cell cell, AbsPath buildFile, Optional<TargetConfiguration> globalTargetConfiguration) {
    SettableFuture<ImmutableList<TargetNodeMaybeIncompatible>> future = SettableFuture.create();
    Pair<AbsPath, Optional<TargetConfiguration>> pathCacheKey =
        new Pair<>(buildFile, globalTargetConfiguration);
    ListenableFuture<ImmutableList<TargetNodeMaybeIncompatible>> cachedFuture =
        allNodeCache.putIfAbsent(pathCacheKey, future);

    if (cachedFuture != null) {
      return cachedFuture;
    }

    try {
      ListenableFuture<List<TargetNodeMaybeIncompatible>> allNodesListJob =
          Futures.transformAsync(
              unconfiguredTargetNodePipeline.getAllNodesJob(cell, buildFile),
              allToConvert -> {
                if (shuttingDown()) {
                  return Futures.immediateCancelledFuture();
                }

                ImmutableList.Builder<ListenableFuture<TargetNodeMaybeIncompatible>> allNodeJobs =
                    ImmutableList.builderWithExpectedSize(allToConvert.size());

                for (UnconfiguredTargetNode from : allToConvert) {
                  ListenableFuture<TargetNodeMaybeIncompatible> targetNode =
                      configureRequestedTarget(
                          cell, from.getBuildTarget(), globalTargetConfiguration, from);
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
  ImmutableList<TargetNodeMaybeIncompatible> getAllRequestedTargetNodes(
      Cell cell, AbsPath buildFile, Optional<TargetConfiguration> globalTargetConfiguration) {
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
  private ListenableFuture<TargetNodeMaybeIncompatible> configureRequestedTarget(
      Cell cell,
      UnconfiguredBuildTarget unconfiguredTarget,
      Optional<TargetConfiguration> globalTargetConfiguration,
      UnconfiguredTargetNode unconfiguredTargetNode) {
    TargetConfiguration targetConfiguration;
    if (unconfiguredTargetNode.getRuleType().getKind() == RuleType.Kind.CONFIGURATION) {
      targetConfiguration = ConfigurationForConfigurationTargets.INSTANCE;
    } else {
      targetConfiguration =
          targetConfigurationForBuildTarget(
              unconfiguredTarget, globalTargetConfiguration, unconfiguredTargetNode);
    }
    BuildTarget configuredTarget = unconfiguredTarget.configure(targetConfiguration);
    return getNodeJobWithRawNode(
        cell,
        configuredTarget,
        DependencyStack.top(configuredTarget),
        Optional.of(unconfiguredTargetNode));
  }

  private TargetConfiguration targetConfigurationForBuildTarget(
      UnconfiguredBuildTarget unconfiguredTarget,
      Optional<TargetConfiguration> globalTargetConfiguration,
      UnconfiguredTargetNode unconfiguredTargetNode) {
    if (globalTargetConfiguration.isPresent()) {
      return globalTargetConfiguration.get();
    }

    // We use `default_target_platform` only when global platform is not specified
    Optional<UnconfiguredBuildTarget> defaultTargetPlatform =
        unconfiguredTargetNode.getDefaultTargetPlatform();
    if (defaultTargetPlatform.isPresent()) {
      return RuleBasedTargetConfiguration.of(
          ConfigurationBuildTargets.convert(defaultTargetPlatform.get()));
    }

    // Use detector when neither global configuration is specified
    // not `default_target_platform` argument is passed
    Optional<TargetConfiguration> detectedTargetConfiguration =
        targetConfigurationDetector.detectTargetConfiguration(unconfiguredTargetNode);
    if (detectedTargetConfiguration.isPresent()) {
      return detectedTargetConfiguration.get();
    }

    if (requireTargetPlatform) {
      throw new HumanReadableException(
          "parser.require_target_platform=true, "
              + "but global --target-platforms= is not specified "
              + "and target %s does not specify default_target_platform "
              + "and detector did not detect the platform",
          unconfiguredTarget);
    }

    return UnconfiguredTargetConfiguration.INSTANCE;
  }

  /** Get build target by name, load if necessary */
  public ListenableFuture<TargetNodeMaybeIncompatible> getNodeJob(
      Cell cell, BuildTarget buildTarget, DependencyStack dependencyStack)
      throws BuildTargetException {
    return getNodeJobWithRawNode(cell, buildTarget, dependencyStack, Optional.empty());
  }

  private ListenableFuture<TargetNodeMaybeIncompatible> getNodeJobWithRawNode(
      Cell cell,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      Optional<UnconfiguredTargetNode> rawNodeIfKnown)
      throws BuildTargetException {
    return cache.getJobWithCacheLookup(
        cell,
        buildTarget,
        () -> {
          if (rawNodeIfKnown.isPresent()) {
            return Futures.submitAsync(
                () -> dispatchComputeNode(cell, buildTarget, dependencyStack, rawNodeIfKnown.get()),
                executorService);
          } else {
            return Futures.transformAsync(
                unconfiguredTargetNodePipeline.getNodeJob(
                    cell, buildTarget.getUnconfiguredBuildTarget(), dependencyStack),
                from -> dispatchComputeNode(cell, buildTarget, dependencyStack, from),
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
  public TargetNodeMaybeIncompatible getNode(
      Cell cell, BuildTarget buildTarget, DependencyStack dependencyStack)
      throws BuildFileParseException, BuildTargetException {
    Preconditions.checkState(!shuttingDown.get());

    try {
      return getNodeJob(cell, buildTarget, dependencyStack).get();
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
