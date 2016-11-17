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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.groups.TargetGroupDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGroup;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.ThreadSafe;

/**
 * This implements a multithreaded pipeline for parsing BUCK files.
 *
 *
 * The high-level flow looks like this:
 *   (in) BuildTarget -> [getRawNodes] -*> [createTargetNode] -> (out) TargetNode
 * (steps in [] have their output cached, -*> means that this step has parallel fanout).
 *
 * The work is simply dumped onto the executor, the {@link ProjectBuildFileParserPool} is used to constrain
 * the number of concurrent active parsers.
 * Within a single pipeline instance work is not duplicated (the **JobsCache variables) are used
 * to make sure we don't schedule the same work more than once), however it's possible for multiple
 * read-only commands to duplicate work.
 */
@ThreadSafe
public class TargetGroupParsePipeline
    extends ConvertingPipeline<Map<String, Object>, TargetGroup> {

  private final ParserTargetNodeFactory<TargetGroup> delegate;
  private final BuckEventBus eventBus;
  private final RawNodeParsePipeline rawNodeParsePipeline;

  /**
   * Create new pipeline for parsing Buck files.
   *  @param cache where to persist results.
   * @param targetNodeDelegate where to farm out the creation of TargetNodes to
   * @param executorService executor
   * @param eventBus bus to use for parse start/stop events
   * @param rawNodeParsePipeline
   */
  public TargetGroupParsePipeline(
      Cache<BuildTarget, TargetGroup> cache,
      ParserTargetNodeFactory<TargetGroup> targetNodeDelegate,
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      RawNodeParsePipeline rawNodeParsePipeline) {
    super(executorService, cache);

    this.delegate = targetNodeDelegate;
    this.eventBus = eventBus;
    this.rawNodeParsePipeline = rawNodeParsePipeline;
  }

  @Override
  protected BuildTarget getBuildTarget(
      Path root, Path buildFile, Map<String, Object> from) {
    return BuildTarget.of(RawNodeParsePipeline.parseBuildTargetFromRawRule(root, from, buildFile));
  }

  @Override
  protected TargetGroup computeNode(
      final Cell cell,
      final BuildTarget buildTarget,
      final Map<String, Object> rawNode) throws BuildTargetException {
    try (final SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
        eventBus,
        PerfEventId.of("GetTargetGroup"),
        "target", buildTarget)) {
      Function<PerfEventId, SimplePerfEvent.Scope> perfEventScopeFunction =
          perfEventId -> SimplePerfEvent.scopeIgnoringShortEvents(
              eventBus,
              perfEventId,
              scope,
              getMinimumPerfEventTimeMs(),
              TimeUnit.MILLISECONDS);
      return delegate.createTargetNode(
          cell,
          cell.getAbsolutePathToBuildFile(buildTarget),
          buildTarget,
          rawNode,
          perfEventScopeFunction);
    }
  }

  @Override
  protected ListenableFuture<ImmutableSet<Map<String, Object>>> getItemsToConvert(
      Cell cell,
      Path buildFile) throws BuildTargetException {
    return rawNodeParsePipeline.getAllNodesJob(cell, buildFile);
  }

  @Override
  protected ListenableFuture<Map<String, Object>> getItemToConvert(
      Cell cell,
      BuildTarget buildTarget) throws BuildTargetException {
    return rawNodeParsePipeline.getNodeJob(cell, buildTarget);
  }

  @Override
  protected boolean isValid(Map<String, Object> from) {
    return super.isValid(from) && isTargetGroup(from);
  }

  public static boolean isTargetGroup(Map<String, Object> from) {
    return Description.getBuildRuleType(TargetGroupDescription.class)
        .getName()
        .equals(from.get(BuckPyFunction.TYPE_PROPERTY_NAME));
  }
}
