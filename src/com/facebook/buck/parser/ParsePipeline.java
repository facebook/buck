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

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;
import static com.google.common.base.Throwables.propagateIfInstanceOf;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypes;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract node parsing pipeline. Allows implementors to define their own logic for creating nodes
 * of type T.
 *
 * @param <T> The type of node this pipeline will produce (raw nodes, target nodes, etc)
 */
public abstract class ParsePipeline<T> implements AutoCloseable {
  private static final Logger LOG = Logger.get(ParsePipeline.class);

  private final AtomicBoolean shuttingDown;
  private final long minimumPerfEventTimeMs;
  protected final BuckEventBus eventBus;

  public ParsePipeline(BuckEventBus eventBus) {
    this.shuttingDown = new AtomicBoolean(false);
    this.minimumPerfEventTimeMs = LOG.isVerboseEnabled() ? 0 : 1;
    this.eventBus = eventBus;
  }

  /**
   * Obtain all {@link TargetNode}s from a build file. This may block if the file is not cached.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @return all targets from the file
   * @throws BuildFileParseException for syntax errors.
   */
  public final ImmutableSet<T> getAllNodes(
      Cell cell, KnownBuildRuleTypes knownBuildRuleTypes, Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException {
    Preconditions.checkState(!shuttingDown.get());

    try {
      return getAllNodesJob(cell, knownBuildRuleTypes, buildFile, processedBytes).get();
    } catch (Exception e) {
      propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
      throw new RuntimeException(e);
    }
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
  public final T getNode(
      Cell cell,
      KnownBuildRuleTypes knownBuildRuleTypes,
      BuildTarget buildTarget,
      AtomicLong processedBytes)
      throws BuildFileParseException, BuildTargetException {
    Preconditions.checkState(!shuttingDown.get());

    try {
      return getNodeJob(cell, knownBuildRuleTypes, buildTarget, processedBytes).get();
    } catch (Exception e) {
      if (e.getCause() != null) {
        propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
        propagateIfInstanceOf(e.getCause(), BuildTargetException.class);
      }
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
      throw new RuntimeException(e);
    }
  }

  /**
   * Asynchronously obtain all {@link TargetNode}s from a build file. This will leverage previously
   * cached raw contents of the file (if present) but will always loop over the contents, so
   * repeated calls (with the same args) are not free.
   *
   * <p>returned future may throw {@link BuildFileParseException} and {@link
   * HumanReadableException}.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @param processedBytes
   * @return future.
   */
  public abstract ListenableFuture<ImmutableSet<T>> getAllNodesJob(
      Cell cell, KnownBuildRuleTypes knownBuildRuleTypes, Path buildFile, AtomicLong processedBytes)
      throws BuildTargetException;

  /**
   * Asynchronously get the {@link TargetNode}. This leverages the cache.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildTarget name of the node we're looking for. The build file path is derived from it.
   * @param processedBytes An accumulator for the number of bytes which were read by the Parser in
   *     order to get this node.
   * @return future.
   * @throws BuildTargetException when the buildTarget is malformed.
   */
  public abstract ListenableFuture<T> getNodeJob(
      Cell cell,
      KnownBuildRuleTypes knownBuildRuleTypes,
      BuildTarget buildTarget,
      AtomicLong processedBytes)
      throws BuildTargetException;

  @Override
  public void close() {
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

  protected final boolean shuttingDown() {
    return shuttingDown.get();
  }

  /**
   * @return minimum duration time for performance events to be logged ( for use with {@link
   *     SimplePerfEvent}s). This is on the base class to make it simpler to enable verbose tracing
   *     for all of the parsing pipelines.
   */
  protected final long getMinimumPerfEventTimeMs() {
    return minimumPerfEventTimeMs;
  }
}
