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

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.GuardedBy;

/**
 * Allows multiple concurrently executing futures to share a constrained number of parsers.
 *
 * Parser instances are lazily created up till a fixed maximum. If more than max parser are
 * requested the associated 'requests' are queued up in the parserRequests field. As soon as
 * a parser is returned it will be used to satisfy the first pending request, otherwise it
 * is stored in the parkedParsers queue.
 */
class ProjectBuildFileParserPool implements AutoCloseable {
  private static final Logger LOG = Logger.get(ProjectBuildFileParserPool.class);

  private final int maxParsersPerCell;
  @GuardedBy("this")
  private final Function<Cell, ProjectBuildFileParser> parserFactory;
  @GuardedBy("this")
  private final Multimap<Cell, ProjectBuildFileParser> createdParsers;
  @GuardedBy("this")
  private final Map<Cell, Deque<ProjectBuildFileParser>> parkedParsers;
  @GuardedBy("this")
  private final Map<Cell, Deque<SettableFuture<Void>>> parserRequests;
  private final AtomicBoolean closing;
  @GuardedBy("this")
  private final Set<ListenableFuture<?>> pendingWork;

  /**
   * @param maxParsersPerCell maximum number of parsers to create for a single cell.
   * @param parserFactory function used to create a new parser.
   */
  public ProjectBuildFileParserPool(
      int maxParsersPerCell,
      Function<Cell, ProjectBuildFileParser> parserFactory) {
    Preconditions.checkArgument(maxParsersPerCell > 0);

    this.maxParsersPerCell = maxParsersPerCell;
    this.parserFactory = parserFactory;
    this.createdParsers = ArrayListMultimap.create();
    this.parkedParsers = new HashMap<>();
    this.parserRequests = new HashMap<>();
    this.closing = new AtomicBoolean(false);
    this.pendingWork = new HashSet<>();
  }

  /**
   * @param cell the cell in which we're parsing
   * @param buildFile the file to parse
   * @param executorService where to perform the parsing.
   * @return a {@link ListenableFuture} containing the result of the parsing. The future will be
   *         cancelled if the {@link ProjectBuildFileParserPool#close()} method is called.
   */
  public synchronized ListenableFuture<ImmutableList<Map<String, Object>>> getAllRulesAndMetaRules(
      final Cell cell,
      final Path buildFile,
      final ListeningExecutorService executorService) {
    Preconditions.checkState(!closing.get());

    final ListenableFuture<ImmutableList<Map<String, Object>>> futureWork = Futures.transformAsync(
        initialSchedule(cell),
        new AsyncFunction<Void, ImmutableList<Map<String, Object>>>() {
          @Override
          public ListenableFuture<ImmutableList<Map<String, Object>>> apply(
              Void input) throws Exception {
            if (closing.get()) {
              return Futures.immediateCancelledFuture();
            }
            Either<ProjectBuildFileParser, ListenableFuture<Void>> parserRequest =
                requestParser(cell);
            if (parserRequest.isLeft()) {
              ProjectBuildFileParser parser = parserRequest.getLeft();
              boolean hadErrorDuringParsing = false;
              try {
                return Futures.immediateFuture(
                    ImmutableList.copyOf(parser.getAllRulesAndMetaRules(buildFile)));
              } catch (BuildFileParseException e) {
                hadErrorDuringParsing = true;
                throw e;
              } finally {
                returnParser(cell, parser, hadErrorDuringParsing);
              }
            } else {
              return Futures.transformAsync(parserRequest.getRight(), this, executorService);
            }
          }
        },
        executorService);

    pendingWork.add(futureWork);
    futureWork.addListener(
        new Runnable() {
          @Override
          public void run() {
            synchronized (ProjectBuildFileParserPool.this) {
              pendingWork.remove(futureWork);
            }
          }
        },
        executorService);

    // If someone else calls cancel on `futureWork` it makes it impossible to wait for that future
    // to finish using the parser.
    return Futures.nonCancellationPropagating(futureWork);
  }

  private synchronized ListenableFuture<Void> initialSchedule(Cell cell) {
    // If we'll (potentially) be allowed to create a parser or there are some parked then we'll take
    // the chance and attempt to run immediately.
    if (allowedToCreateParser(cell) || !getParkedParserQueue(cell).isEmpty()) {
      return Futures.immediateFuture(null);
    }
    // All possible parsers are currently occupied. Because we're in a synchronized block, even
    // if one becomes available immediately after this call returns it will simply make this future
    // runnable, so we'll be able to progress.
    return scheduleNewParserRequest(cell);
  }

  private synchronized Either<ProjectBuildFileParser, ListenableFuture<Void>> requestParser(
      Cell cell) {
    Optional<ProjectBuildFileParser> parser = obtainParser(cell);
    if (parser.isPresent()) {
      return Either.ofLeft(parser.get());
    }
    return Either.ofRight(scheduleNewParserRequest(cell));
  }

  private synchronized ListenableFuture<Void> scheduleNewParserRequest(Cell cell) {
    if (closing.get()) {
      return Futures.immediateFuture(null);
    }
    SettableFuture<Void> parserFuture = SettableFuture.create();
    Deque<SettableFuture<Void>> requestsQueue = parserRequests.get(cell);
    if (requestsQueue == null) {
      requestsQueue = new ArrayDeque<>();
      parserRequests.put(cell, requestsQueue);
    }
    requestsQueue.add(parserFuture);
    return parserFuture;
  }

  private synchronized Deque<ProjectBuildFileParser> getParkedParserQueue(Cell cell) {
    Deque<ProjectBuildFileParser> parkedParsersQueue = parkedParsers.get(cell);
    if (parkedParsersQueue == null) {
      parkedParsersQueue = new ArrayDeque<>(maxParsersPerCell);
      parkedParsers.put(cell, parkedParsersQueue);
    }
    return parkedParsersQueue;
  }

  private synchronized Optional<ProjectBuildFileParser> obtainParser(Cell cell) {
    Deque<ProjectBuildFileParser> parserQueue = getParkedParserQueue(cell);
    ProjectBuildFileParser parser = parserQueue.pollFirst();
    if (parser != null) {
      return Optional.of(parser);
    }
    return createIfAllowed(cell);
  }

  private synchronized void returnParser(
      Cell cell,
      ProjectBuildFileParser parser,
      boolean parserIsDefunct) {
    if (parserIsDefunct) {
      createdParsers.remove(cell, parser);
      try {
        parser.close();
      } catch (Exception e) {
        LOG.info(e, "Error shutting down a defunct parser.");
      }
    } else {
      Deque<ProjectBuildFileParser> parkedParsersQueue = getParkedParserQueue(cell);
      parkedParsersQueue.add(parser);
    }
    scheduleNextRequest(cell);
  }

  private synchronized void scheduleNextRequest(Cell cell) {
    if (!parserRequests.containsKey(cell)) {
      return;
    }

    while (true) {
      SettableFuture<Void> nextRequest = parserRequests.get(cell).pollFirst();
      // Queue empty.
      if (nextRequest == null) {
        return;
      }
      // A false return value means the future was failed/cancelled, so we ignore it.
      if (nextRequest.set(null)) {
        return;
      }
    }
  }

  private synchronized boolean allowedToCreateParser(Cell cell) {
    return !closing.get() && (createdParsers.get(cell).size() < maxParsersPerCell);
  }

  private synchronized Optional<ProjectBuildFileParser> createIfAllowed(Cell cell) {
    if (!allowedToCreateParser(cell)) {
      return Optional.absent();
    }
    ProjectBuildFileParser parser = Preconditions.checkNotNull(parserFactory.apply(cell));
    createdParsers.put(cell, parser);
    return Optional.of(parser);
  }

  @Override
  public synchronized void close() {
    Preconditions.checkState(!closing.get());
    closing.set(true);

    // Unblock all waiting requests.
    for (Deque<SettableFuture<Void>> requestQueue : parserRequests.values()) {
      for (SettableFuture<Void> request : requestQueue) {
        request.set(null);
      }
    }

    // Any parsing that is currently taking place will be allowed to complete (as it won't notice
    // `closing` is true.
    // Any scheduled (but not executing) parse requests should notice `closing` is true and
    // mark themselves as cancelled.
    // Therefore `closeFuture` should allow us to wait for any parsers that are in use.
    ListenableFuture<List<Object>> closeFuture;
    closeFuture = Futures.successfulAsList(pendingWork);

    // As silly as it seems this is the only reliable way to make sure we run the shutdown code.
    // Reusing an external executor means we run the risk of it being shut down before the cleanup
    // future is ready to run (which causes it to never run).
    // Using a direct executor means we run the chance of executing shutdown synchronously (which
    // we try to avoid).
    final ExecutorService executorService =
        MoreExecutors.newSingleThreadExecutor("parser shutdown");

    // It is possible that more requests for work are scheduled at this point, however they should
    // all early-out due to `closing` being set to true, so we don't really care about those.
    Futures.transformAsync(
        closeFuture,
        new AsyncFunction<List<Object>, Void>() {
          @Override
          public ListenableFuture<Void> apply(List<Object> input) throws Exception {
            int parkedParsersCount = 0;
            for (Deque<ProjectBuildFileParser> projectBuildFileParsers : parkedParsers.values()) {
              parkedParsersCount += projectBuildFileParsers.size();
            }
            if (parkedParsersCount != createdParsers.size()) {
              LOG.error("Whoops! Some parser are still in use, even though we're shutting down..");
            }
            // Now that pending work is done we can close all parsers.
            for (Map.Entry<Cell, ProjectBuildFileParser> createdParserEntry :
                createdParsers.entries()) {
              createdParserEntry.getValue().close();
            }
            for (Map.Entry<Cell, Deque<SettableFuture<Void>>> cellDequeEntry :
                parserRequests.entrySet()) {
              if (!cellDequeEntry.getValue().isEmpty()) {
                LOG.error("Error shutting down ParserLeaseVendor: " +
                        "there should be no enqueued parser requests.");
                break;
              }
            }
            executorService.shutdown();
            return Futures.immediateFuture(null);
          }
        },
        executorService);
  }
}
