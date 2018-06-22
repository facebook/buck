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
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypes;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** {@link ConvertingPipeline} that computes a node in a {@link SimplePerfEvent} scope. */
public abstract class ConvertingPipelineWithPerfEventScope<F, T> extends ConvertingPipeline<F, T> {

  private final SimplePerfEvent.Scope perfEventScope;
  private final PerfEventId perfEventId;

  public ConvertingPipelineWithPerfEventScope(
      ListeningExecutorService executorService,
      Cache<BuildTarget, T> cache,
      BuckEventBus eventBus,
      Scope perfEventScope,
      PerfEventId perfEventId) {
    super(executorService, cache, eventBus);
    this.perfEventScope = perfEventScope;
    this.perfEventId = perfEventId;
  }

  @Override
  protected final T computeNode(
      Cell cell,
      KnownBuildRuleTypes knownBuildRuleTypes,
      BuildTarget buildTarget,
      F rawNode,
      AtomicLong processedBytes)
      throws BuildTargetException {

    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scopeIgnoringShortEvents(
            eventBus,
            perfEventId,
            "target",
            buildTarget,
            perfEventScope,
            getMinimumPerfEventTimeMs(),
            TimeUnit.MILLISECONDS)) {
      Function<PerfEventId, Scope> perfEventScopeFunction =
          perfEventId ->
              SimplePerfEvent.scopeIgnoringShortEvents(
                  eventBus, perfEventId, scope, getMinimumPerfEventTimeMs(), TimeUnit.MILLISECONDS);

      return computeNodeInScope(
          cell, knownBuildRuleTypes, buildTarget, rawNode, processedBytes, perfEventScopeFunction);
    }
  }

  protected abstract T computeNodeInScope(
      Cell cell,
      KnownBuildRuleTypes knownBuildRuleTypes,
      BuildTarget buildTarget,
      F rawNode,
      AtomicLong processedBytes,
      Function<PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException;

  @Override
  public void close() {
    perfEventScope.close();
    super.close();
  }
}
