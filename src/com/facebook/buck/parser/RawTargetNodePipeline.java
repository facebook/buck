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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Converts nodes in a raw form (taken from build file parsers) into {@link RawTargetNode}. */
public class RawTargetNodePipeline extends ConvertingPipeline<Map<String, Object>, RawTargetNode> {

  private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;
  private final BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline;
  private final RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory;

  public RawTargetNodePipeline(
      ListeningExecutorService executorService,
      Cache<BuildTarget, RawTargetNode> cache,
      BuckEventBus eventBus,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline,
      BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline,
      RawTargetNodeFactory<Map<String, Object>> rawTargetNodeFactory) {
    super(
        executorService,
        cache,
        eventBus,
        SimplePerfEvent.scope(eventBus, PerfEventId.of("raw_target_node_parse_pipeline")),
        PerfEventId.of("GetRawTargetNode"));
    this.buildFileRawNodeParsePipeline = buildFileRawNodeParsePipeline;
    this.buildTargetRawNodeParsePipeline = buildTargetRawNodeParsePipeline;
    this.rawTargetNodeFactory = rawTargetNodeFactory;
  }

  @Override
  protected BuildTarget getBuildTarget(
      Path root,
      Optional<String> cellName,
      Path buildFile,
      TargetConfiguration targetConfiguration,
      Map<String, Object> from) {
    return ImmutableUnconfiguredBuildTargetView.of(
            UnflavoredBuildTargetFactory.createFromRawNode(root, cellName, from, buildFile))
        // This pipeline provides access to RawTargetNode which doesn't know about target
        // configuration. Using empty configuration here to make sure raw target nodes are not
        // duplicated for different configurations.
        // TODO: remove this when pipeline cache supports RawTargetNode
        .configure(EmptyTargetConfiguration.INSTANCE);
  }

  @Override
  protected RawTargetNode computeNodeInScope(
      Cell cell,
      BuildTarget buildTarget,
      Map<String, Object> rawNode,
      Function<PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException {
    return rawTargetNodeFactory.create(
        cell,
        cell.getBuckConfigView(ParserConfig.class)
            .getAbsolutePathToBuildFile(cell, buildTarget.getUnconfiguredBuildTargetView()),
        buildTarget.getUnconfiguredBuildTargetView(),
        rawNode);
  }

  @Override
  protected ListenableFuture<ImmutableList<Map<String, Object>>> getItemsToConvert(
      Cell cell, Path buildFile) throws BuildTargetException {
    return Futures.transform(
        buildFileRawNodeParsePipeline.getAllNodesJob(cell, buildFile),
        map -> ImmutableList.copyOf(map.getTargets().values()),
        MoreExecutors.directExecutor());
  }

  @Override
  protected ListenableFuture<Map<String, Object>> getItemToConvert(
      Cell cell, BuildTarget buildTarget) throws BuildTargetException {
    return buildTargetRawNodeParsePipeline.getNodeJob(
        cell, buildTarget.getUnconfiguredBuildTargetView());
  }
}
