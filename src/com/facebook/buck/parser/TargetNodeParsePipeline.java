/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.ImmutableBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This implements a multithreaded pipeline for parsing BUCK files.
 *
 * <p>The high-level flow looks like this: (in) BuildTarget -> [getRawNodes] -*> [createTargetNode]
 * -> (out) TargetNode (steps in [] have their output cached, -*> means that this step has parallel
 * fanout).
 *
 * <p>The work is simply dumped onto the executor, the {@link ProjectBuildFileParserPool} is used to
 * constrain the number of concurrent active parsers. Within a single pipeline instance work is not
 * duplicated (the **JobsCache variables) are used to make sure we don't schedule the same work more
 * than once), however it's possible for multiple read-only commands to duplicate work.
 */
@ThreadSafe
public class TargetNodeParsePipeline
    extends ConvertingPipeline<Map<String, Object>, TargetNode<?>> {

  private static final Logger LOG = Logger.get(TargetNodeParsePipeline.class);

  private final ParserTargetNodeFactory<Map<String, Object>> delegate;
  private final boolean speculativeDepsTraversal;
  private final RawNodeParsePipeline rawNodeParsePipeline;

  /**
   * Create new pipeline for parsing Buck files.
   *
   * @param cache where to persist results.
   * @param targetNodeDelegate where to farm out the creation of TargetNodes to
   * @param executorService executor
   * @param eventBus bus to use for parse start/stop events
   * @param speculativeDepsTraversal whether to automatically schedule parsing of nodes' deps in the
   */
  public TargetNodeParsePipeline(
      Cache<BuildTarget, TargetNode<?>> cache,
      ParserTargetNodeFactory<Map<String, Object>> targetNodeDelegate,
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      boolean speculativeDepsTraversal,
      RawNodeParsePipeline rawNodeParsePipeline) {
    super(
        executorService,
        cache,
        eventBus,
        SimplePerfEvent.scope(eventBus, PerfEventId.of("target_node_parse_pipeline")),
        PerfEventId.of("GetTargetNode"));

    this.delegate = targetNodeDelegate;
    this.speculativeDepsTraversal = speculativeDepsTraversal;
    this.rawNodeParsePipeline = rawNodeParsePipeline;
  }

  @Override
  protected BuildTarget getBuildTarget(
      Path root, Optional<String> cellName, Path buildFile, Map<String, Object> from) {
    return ImmutableBuildTarget.of(
        RawNodeParsePipeline.parseBuildTargetFromRawRule(root, cellName, from, buildFile));
  }

  @Override
  @SuppressWarnings("CheckReturnValue") // submit value is not used
  protected TargetNode<?> computeNodeInScope(
      Cell cell,
      BuildTarget buildTarget,
      Map<String, Object> rawNode,
      Function<PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException {
    TargetNode<?> targetNode =
        delegate.createTargetNode(
            cell,
            cell.getAbsolutePathToBuildFile(buildTarget),
            buildTarget,
            rawNode,
            perfEventScopeFunction);

    if (speculativeDepsTraversal) {
      executorService.submit(
          () -> {
            for (BuildTarget depTarget : targetNode.getParseDeps()) {
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

  @Override
  protected ListenableFuture<ImmutableSet<Map<String, Object>>> getItemsToConvert(
      Cell cell, Path buildFile) throws BuildTargetException {
    return rawNodeParsePipeline.getAllNodesJob(cell, buildFile);
  }

  @Override
  protected ListenableFuture<Map<String, Object>> getItemToConvert(
      Cell cell, BuildTarget buildTarget) throws BuildTargetException {
    return rawNodeParsePipeline.getNodeJob(cell, buildTarget);
  }
}
