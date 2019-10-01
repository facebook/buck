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
import com.facebook.buck.core.model.AbstractRuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Converts nodes in a raw form (taken from build file parsers) into {@link RawTargetNode}. */
public class RawTargetNodePipeline implements AutoCloseable {
  private static final Logger LOG = Logger.get(RawTargetNodePipeline.class);

  private final ListeningExecutorService executorService;
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private final BuckEventBus eventBus;
  private final PipelineNodeCache<UnconfiguredBuildTargetView, RawTargetNode> cache;
  private final ConcurrentHashMap<Path, ListenableFuture<ImmutableList<RawTargetNode>>>
      allNodeCache = new ConcurrentHashMap<>();
  private final Scope perfEventScope;
  private final PerfEventId perfEventId;
  /**
   * minimum duration time for performance events to be logged (for use with {@link
   * SimplePerfEvent}s). This is on the base class to make it simpler to enable verbose tracing for
   * all of the parsing pipelines.
   */
  private final long minimumPerfEventTimeMs;

  private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;
  private final BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline;
  private final RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory;

  public RawTargetNodePipeline(
      ListeningExecutorService executorService,
      Cache<UnconfiguredBuildTargetView, RawTargetNode> cache,
      BuckEventBus eventBus,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline,
      BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline,
      RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory) {
    this.executorService = executorService;
    this.eventBus = eventBus;
    this.buildFileRawNodeParsePipeline = buildFileRawNodeParsePipeline;
    this.buildTargetRawNodeParsePipeline = buildTargetRawNodeParsePipeline;
    this.rawTargetNodeFactory = rawTargetNodeFactory;
    this.minimumPerfEventTimeMs = LOG.isVerboseEnabled() ? 0 : 10;
    this.perfEventId = PerfEventId.of("GetRawTargetNode");
    this.perfEventScope =
        SimplePerfEvent.scope(eventBus, PerfEventId.of("raw_target_node_parse_pipeline"));
    this.cache = new PipelineNodeCache<>(cache, RawTargetNodePipeline::targetNodeIsConfiguration);
  }

  private static boolean targetNodeIsConfiguration(RawTargetNode targetNode) {
    return targetNode.getRuleType().getKind() == AbstractRuleType.Kind.CONFIGURATION;
  }

  /** Get or load all raw target nodes from a build file */
  public ListenableFuture<ImmutableList<RawTargetNode>> getAllNodesJob(Cell cell, Path buildFile) {
    SettableFuture<ImmutableList<RawTargetNode>> future = SettableFuture.create();
    ListenableFuture<ImmutableList<RawTargetNode>> cachedFuture =
        allNodeCache.putIfAbsent(buildFile, future);

    if (cachedFuture != null) {
      return cachedFuture;
    }

    try {
      ListenableFuture<List<RawTargetNode>> allNodesListJob =
          Futures.transformAsync(
              buildFileRawNodeParsePipeline.getAllNodesJob(cell, buildFile),
              buildFileManifest -> {
                ImmutableList<Map<String, Object>> allToConvert =
                    ImmutableList.copyOf(buildFileManifest.getTargets().values());
                if (shuttingDown()) {
                  return Futures.immediateCancelledFuture();
                }

                ImmutableList.Builder<ListenableFuture<RawTargetNode>> allNodeJobs =
                    ImmutableList.builderWithExpectedSize(allToConvert.size());

                for (Map<String, Object> from : allToConvert) {
                  UnconfiguredBuildTargetView target =
                      ImmutableUnconfiguredBuildTargetView.of(
                          UnflavoredBuildTargetFactory.createFromRawNode(
                              cell.getRoot(), cell.getCanonicalName(), from, buildFile));
                  allNodeJobs.add(
                      cache.getJobWithCacheLookup(
                          cell, target, () -> dispatchComputeNode(cell, target, from), eventBus));
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

  /** Get build target by name, load if necessary */
  public ListenableFuture<RawTargetNode> getNodeJob(
      Cell cell, UnconfiguredBuildTargetView buildTarget) throws BuildTargetException {
    return cache.getJobWithCacheLookup(
        cell,
        buildTarget,
        () ->
            Futures.transformAsync(
                buildTargetRawNodeParsePipeline.getNodeJob(cell, buildTarget),
                from -> dispatchComputeNode(cell, buildTarget, from),
                executorService),
        eventBus);
  }

  private ListenableFuture<RawTargetNode> dispatchComputeNode(
      Cell cell, UnconfiguredBuildTargetView buildTarget, Map<String, Object> from)
      throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }
    RawTargetNode result;

    try (Scope scope =
        SimplePerfEvent.scopeIgnoringShortEvents(
            eventBus,
            perfEventId,
            "target",
            buildTarget,
            perfEventScope,
            minimumPerfEventTimeMs,
            TimeUnit.MILLISECONDS)) {
      result =
          rawTargetNodeFactory.create(
              cell,
              cell.getBuckConfigView(ParserConfig.class)
                  .getAbsolutePathToBuildFile(cell, buildTarget),
              buildTarget,
              from);
    }
    return Futures.immediateFuture(result);
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
