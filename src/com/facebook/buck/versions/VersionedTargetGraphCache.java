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

package com.facebook.buck.versions;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.cache.CacheStatsTracker;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

public class VersionedTargetGraphCache {

  // How many times to attempt to build a version target graph in the face of timeouts.
  private static final int ATTEMPTS = 3;

  private static final Logger LOG = Logger.get(VersionedTargetGraphCache.class);

  @Nullable private CachedVersionedTargetGraph cachedVersionedTargetGraph = null;

  /** @return a new versioned target graph. */
  private TargetGraphCreationResult createdVersionedTargetGraph(
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      long timeoutSeconds,
      TargetGraphCreationResult targetGraphCreationResult,
      Cells cells)
      throws VersionException, TimeoutException, InterruptedException {

    TargetGraphCreationResult versionedTargetGraph =
        AsyncVersionedTargetGraphBuilder.transform(
            new VersionUniverseVersionSelector(
                targetGraphCreationResult.getTargetGraph(), versionUniverses),
            targetGraphCreationResult,
            depsAwareExecutor,
            typeCoercerFactory,
            unconfiguredBuildTargetFactory,
            timeoutSeconds,
            cells);
    return versionedTargetGraph;
  }

  private VersionedTargetGraphCacheResult getVersionedTargetGraph(
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      long timeoutSeconds,
      CacheStatsTracker statsTracker,
      TargetGraphCreationResult targetGraphCreationResult,
      Cells cells)
      throws VersionException, TimeoutException, InterruptedException {

    CacheStatsTracker.CacheRequest request = statsTracker.startRequest();

    // If new inputs match old ones, we can used the cached graph, if present.
    VersionedTargetGraphInputs newInputs =
        ImmutableVersionedTargetGraphInputs.of(targetGraphCreationResult, versionUniverses);
    if (cachedVersionedTargetGraph != null
        && newInputs.equals(cachedVersionedTargetGraph.getInputs())) {

      VersionedTargetGraphCacheResult result =
          ImmutableVersionedTargetGraphCacheResult.of(
              ResultType.HIT, cachedVersionedTargetGraph.getTargetGraphCreationResult());

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

    TargetGraphCreationResult newVersionedTargetGraph =
        createdVersionedTargetGraph(
            depsAwareExecutor,
            versionUniverses,
            typeCoercerFactory,
            unconfiguredBuildTargetFactory,
            timeoutSeconds,
            targetGraphCreationResult,
            cells);
    cachedVersionedTargetGraph =
        ImmutableCachedVersionedTargetGraph.of(newInputs, newVersionedTargetGraph);
    VersionedTargetGraphCacheResult result =
        ImmutableVersionedTargetGraphCacheResult.of(resultType, newVersionedTargetGraph);

    request.recordLoadSuccess();

    return result;
  }

  /**
   * @return a versioned target graph, either generated from the parameters or retrieved from a
   *     cache.
   */
  public VersionedTargetGraphCacheResult getVersionedTargetGraph(
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      BuckConfig buckConfig,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      TargetGraphCreationResult targetGraphCreationResult,
      Optional<TargetConfiguration> targetConfiguration,
      CacheStatsTracker statsTracker,
      BuckEventBus eventBus,
      Cells cells)
      throws VersionException, InterruptedException {

    VersionBuckConfig versionBuckConfig = new VersionBuckConfig(buckConfig);
    ImmutableMap<String, VersionUniverse> versionUniverses =
        versionBuckConfig.getVersionUniverses(targetConfiguration);

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
                  depsAwareExecutor,
                  versionUniverses,
                  typeCoercerFactory,
                  unconfiguredBuildTargetFactory,
                  versionBuckConfig.getVersionTargetGraphTimeoutSeconds(),
                  statsTracker,
                  targetGraphCreationResult,
                  cells);
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
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      TargetGraphCreationResult targetGraphCreationResult,
      CacheStatsTracker statsTracker,
      Cells cells)
      throws VersionException, InterruptedException, TimeoutException {
    return getVersionedTargetGraph(
        depsAwareExecutor,
        versionUniverses,
        typeCoercerFactory,
        unconfiguredBuildTargetFactory,
        20,
        statsTracker,
        targetGraphCreationResult,
        cells);
  }

  /**
   * A collection of anything which affects/changes how the versioned target graph is generated. If
   * any of these items changes between runs, we cannot use the cached versioned target graph and
   * must re-generate it.
   */
  @BuckStyleValue
  interface VersionedTargetGraphInputs {

    /** @return the un-versioned target graph to be transformed. */
    TargetGraphCreationResult getTargetGraphCreationResult();

    /** @return the version universes used when generating the versioned target graph. */
    ImmutableMap<String, VersionUniverse> getVersionUniverses();
  }

  /**
   * Tuple to store the previously cached versioned target graph along with all inputs that affect
   * how it's generated (for invalidation detection).
   */
  @BuckStyleValue
  interface CachedVersionedTargetGraph {

    /** @return any inputs which, when changed, may produce a different versioned target graph. */
    VersionedTargetGraphInputs getInputs();

    /** @return a versioned target graph. */
    TargetGraphCreationResult getTargetGraphCreationResult();
  }

  @BuckStyleValue
  interface VersionedTargetGraphCacheResult {

    /** @return the type of result. */
    ResultType getType();

    /** @return a versioned target graph. */
    TargetGraphCreationResult getTargetGraphCreationResult();
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
