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

package com.facebook.buck.versions;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutorWithLocalStack;
import com.facebook.buck.core.graph.transformation.executor.impl.JavaExecutorBackedDefaultDepsAwareExecutor;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.cache.CacheStatsTracker;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public class VersionedTargetGraphCache {

  // How many times to attempt to build a version target graph in the face of timeouts.
  private static final int ATTEMPTS = 3;

  private static final Logger LOG = Logger.get(VersionedTargetGraphCache.class);

  @Nullable private CachedVersionedTargetGraph cachedVersionedTargetGraph = null;

  /** @return a new versioned target graph. */
  private TargetGraphAndBuildTargets createdVersionedTargetGraph(
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      ForkJoinPool pool,
      TypeCoercerFactory typeCoercerFactory,
      VersionTargetGraphMode versionTargetGraphMode,
      Map<VersionTargetGraphMode, Double> versionTargetGraphModeProbabilities,
      long timeoutSeconds,
      BuckEventBus eventBus)
      throws VersionException, TimeoutException, InterruptedException {

    VersionTargetGraphMode resolvedMode = versionTargetGraphMode;
    if (resolvedMode == VersionTargetGraphMode.EXPERIMENT) {
      if (versionTargetGraphModeProbabilities.isEmpty()) {
        resolvedMode =
            RandomizedTrial.getGroup(
                "async_version_tg_builder",
                eventBus.getBuildId().toString(),
                VersionTargetGraphMode.class);
      } else {
        resolvedMode =
            RandomizedTrial.getGroup(
                "async_version_tg_builder",
                eventBus.getBuildId().toString(),
                versionTargetGraphModeProbabilities);
      }
    }
    Preconditions.checkState(resolvedMode != VersionTargetGraphMode.EXPERIMENT);
    eventBus.post(
        new ExperimentEvent("async_version_tg_builder", resolvedMode.toString(), "", null, null));

    if (resolvedMode == VersionTargetGraphMode.DISABLED) {
      return ParallelVersionedTargetGraphBuilder.transform(
          new VersionUniverseVersionSelector(
              targetGraphAndBuildTargets.getTargetGraph(), versionUniverses),
          targetGraphAndBuildTargets,
          pool,
          typeCoercerFactory,
          timeoutSeconds);
    } else {
      DepsAwareExecutor<TargetNode<?>, ?> executor;
      switch (resolvedMode) {
        case ENABLED:
          executor = DefaultDepsAwareExecutor.from(pool);
          break;
        case ENABLED_LS:
          executor = DefaultDepsAwareExecutorWithLocalStack.from(pool);
          break;
        case ENABLED_JE:
          executor = JavaExecutorBackedDefaultDepsAwareExecutor.from(pool);
          break;
        case DISABLED:
          throw new AssertionError("Disabled should be handled already");
        case EXPERIMENT:
        default:
          throw new AssertionError(
              "EXPERIMENT values should have been resolved to ENABLED or DISABLED.");
      }
      TargetGraphAndBuildTargets versionedTargetGraph =
          AsyncVersionedTargetGraphBuilder.transform(
              new VersionUniverseVersionSelector(
                  targetGraphAndBuildTargets.getTargetGraph(), versionUniverses),
              targetGraphAndBuildTargets,
              executor,
              typeCoercerFactory,
              timeoutSeconds);
      executor.shutdownNow();
      return versionedTargetGraph;
    }
  }

  private VersionedTargetGraphCacheResult getVersionedTargetGraph(
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      ForkJoinPool pool,
      TypeCoercerFactory typeCoercerFactory,
      VersionTargetGraphMode versionTargetGraphMode,
      Map<VersionTargetGraphMode, Double> versionTargetGraphModeProbabilities,
      long timeoutSeconds,
      BuckEventBus eventBus,
      CacheStatsTracker statsTracker)
      throws VersionException, TimeoutException, InterruptedException {

    CacheStatsTracker.CacheRequest request = statsTracker.startRequest();

    // If new inputs match old ones, we can used the cached graph, if present.
    VersionedTargetGraphInputs newInputs =
        VersionedTargetGraphInputs.of(targetGraphAndBuildTargets, versionUniverses);
    if (cachedVersionedTargetGraph != null
        && newInputs.equals(cachedVersionedTargetGraph.getInputs())) {

      VersionedTargetGraphCacheResult result =
          VersionedTargetGraphCacheResult.of(
              ResultType.HIT, cachedVersionedTargetGraph.getTargetGraphAndBuildTargets());

      request.recordHit();

      return result;
    }

    // Build and cache new versioned target graph.
    ResultType resultType;
    if (cachedVersionedTargetGraph == null) {
      request.recordMiss();
      resultType = ResultType.EMPTY;
    } else {
      request.recordMissMatch();
      resultType = ResultType.MISMATCH;
    }

    TargetGraphAndBuildTargets newVersionedTargetGraph =
        createdVersionedTargetGraph(
            targetGraphAndBuildTargets,
            versionUniverses,
            pool,
            typeCoercerFactory,
            versionTargetGraphMode,
            versionTargetGraphModeProbabilities,
            timeoutSeconds,
            eventBus);
    cachedVersionedTargetGraph = CachedVersionedTargetGraph.of(newInputs, newVersionedTargetGraph);
    VersionedTargetGraphCacheResult result =
        VersionedTargetGraphCacheResult.of(resultType, newVersionedTargetGraph);

    request.recordLoadSuccess();

    return result;
  }

  /**
   * @return a versioned target graph, either generated from the parameters or retrieved from a
   *     cache.
   */
  public VersionedTargetGraphCacheResult getVersionedTargetGraph(
      BuckEventBus eventBus,
      TypeCoercerFactory typeCoercerFactory,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      ForkJoinPool pool,
      VersionBuckConfig versionBuckConfig,
      CacheStatsTracker statsTracker)
      throws VersionException, InterruptedException {

    VersionedTargetGraphEvent.Started started = VersionedTargetGraphEvent.started();
    eventBus.post(started);
    try {

      // TODO(agallagher): There are occasional deadlocks happening inside the `ForkJoinPool` used
      // by the `VersionedTargetGraphBuilder`, and it's not clear if this is from our side and if
      // so, where we're causing this.  So in the meantime, we build-in a timeout into the builder
      // and catch it here, performing retries and logging.
      for (int attempt = 1; ; attempt++) {
        try {
          VersionedTargetGraphCacheResult result =
              getVersionedTargetGraph(
                  targetGraphAndBuildTargets,
                  versionUniverses,
                  pool,
                  typeCoercerFactory,
                  versionBuckConfig.getVersionTargetGraphMode(),
                  versionBuckConfig.getVersionTargetGraphModeGroups(),
                  versionBuckConfig.getVersionTargetGraphTimeoutSeconds(),
                  eventBus,
                  statsTracker);
          LOG.info("versioned target graph " + result.getType().getDescription());
          eventBus.post(result.getType().getEvent());
          return result;
        } catch (TimeoutException e) {
          eventBus.post(VersionedTargetGraphEvent.timeout());
          LOG.warn("Timed out building versioned target graph.");
          Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
          StringBuilder traces = new StringBuilder(stackTraces.size());
          for (Entry<Thread, StackTraceElement[]> trace : stackTraces.entrySet()) {
            traces.append("Thread [");
            traces.append(trace.getKey().getName());
            traces.append("],stack:[");
            Joiner.on(", ").appendTo(traces, trace.getValue());
            traces.append("],");
          }
          LOG.info(traces.toString());
          if (attempt < ATTEMPTS) continue;
          throw new RuntimeException(e);
        }
      }
    } finally {
      eventBus.post(VersionedTargetGraphEvent.finished(started));
    }
  }

  public VersionedTargetGraphCacheResult toVersionedTargetGraph(
      BuckEventBus eventBus,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      TypeCoercerFactory typeCoercerFactory,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      ForkJoinPool pool,
      CacheStatsTracker statsTracker)
      throws VersionException, InterruptedException, TimeoutException {
    return getVersionedTargetGraph(
        targetGraphAndBuildTargets,
        versionUniverses,
        pool,
        typeCoercerFactory,
        VersionTargetGraphMode.DISABLED,
        ImmutableMap.of(),
        20,
        eventBus,
        statsTracker);
  }

  /**
   * A collection of anything which affects/changes how the versioned target graph is generated. If
   * any of these items changes between runs, we cannot use the cached versioned target graph and
   * must re-generate it.
   */
  @Value.Immutable
  @BuckStyleTuple
  interface AbstractVersionedTargetGraphInputs {

    /** @return the un-versioned target graph to be transformed. */
    TargetGraphAndBuildTargets getTargetGraphAndBuildTargets();

    /** @return the version universes used when generating the versioned target graph. */
    ImmutableMap<String, VersionUniverse> getVersionUniverses();
  }

  /**
   * Tuple to store the previously cached versioned target graph along with all inputs that affect
   * how it's generated (for invalidation detection).
   */
  @Value.Immutable
  @BuckStyleTuple
  interface AbstractCachedVersionedTargetGraph {

    /** @return any inputs which, when changed, may produce a different versioned target graph. */
    VersionedTargetGraphInputs getInputs();

    /** @return a versioned target graph. */
    TargetGraphAndBuildTargets getTargetGraphAndBuildTargets();
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractVersionedTargetGraphCacheResult {

    /** @return the type of result. */
    ResultType getType();

    /** @return a versioned target graph. */
    TargetGraphAndBuildTargets getTargetGraphAndBuildTargets();
  }

  /** The possible result types using the cache. */
  public enum ResultType {

    /** A miss in the cache due to the inputs changing. */
    MISMATCH {
      @Override
      BuckEvent getEvent() {
        return VersionedTargetGraphEvent.Cache.miss();
      }

      @Override
      String getDescription() {
        return "cache miss (mismatch)";
      }
    },

    /** A miss in the cache due to the cache being empty. */
    EMPTY {
      @Override
      BuckEvent getEvent() {
        return VersionedTargetGraphEvent.Cache.miss();
      }

      @Override
      String getDescription() {
        return "cache miss (empty)";
      }
    },

    /** A hit in the cache. */
    HIT {
      @Override
      BuckEvent getEvent() {
        return VersionedTargetGraphEvent.Cache.hit();
      }

      @Override
      String getDescription() {
        return "cache hit";
      }
    },
    ;

    abstract BuckEvent getEvent();

    abstract String getDescription();
  }
}
