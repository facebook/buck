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

import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.Cell;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Allows multiple concurrently executing futures to share a constrained number of parsers.
 *
 * Parser instances are lazily created up till a fixed maximum. If more than max parser are
 * requested the associated 'requests' are queued up in the parserRequests field. As soon as
 * a parser is returned it will be used to satisfy the first pending request, otherwise it
 * is stored in the parkedParsers queue.
 */
class ParserLeaseVendor<P extends AutoCloseable> implements AutoCloseable {
  private final int maxParsersPerCell;
  private final Function<Cell, P> parserFactory;
  private final Multimap<Cell, P> createdParsers;
  private final Map<Cell, Deque<P>> parkedParsers;
  private final Map<Cell, Deque<SettableFuture<P>>> parserRequests;
  private final AtomicBoolean closing;
  private final Set<ListenableFuture<?>> pendingLeases;

  private static final Logger LOG = Logger.get(ParserLeaseVendor.class);

  /**
   * @param maxParsersPerCell maximum number of parsers to create for a single cell.
   * @param parserFactory function used to create a new parser.
   */
  public ParserLeaseVendor(
      int maxParsersPerCell,
      Function<Cell, P> parserFactory) {
    this.maxParsersPerCell = maxParsersPerCell;
    this.parserFactory = parserFactory;
    this.createdParsers = ArrayListMultimap.create();
    this.parkedParsers = new HashMap<>();
    this.parserRequests = new HashMap<>();
    this.closing = new AtomicBoolean(false);
    this.pendingLeases = new HashSet<>();
  }

  /**
   * @param cell the cell in which we're parsing
   * @param withParser the function that performs the interaction with the parser
   * @param executorService where to apply the async function
   * @param <T> type of result
   * @return a {@link ListenableFuture} that will run the supplied AsyncFunction when a parser
   *         is available. Never cancel this future, the code doesn't handle that case.
   */
  public <T> ListenableFuture<T> leaseParser(
      final Cell cell,
      final AsyncFunction<P, T> withParser,
      ListeningExecutorService executorService) {
    Preconditions.checkState(!closing.get());

    final ListenableFuture<P> obtainedParser = obtainParser(cell);
    final ListenableFuture<T> futureWork = Futures.transformAsync(
        obtainedParser,
        new AsyncFunction<P, T>() {
          @Override
          public ListenableFuture<T> apply(P input) throws Exception {
            if (closing.get()) {
              return Futures.immediateCancelledFuture();
            }
            return withParser.apply(input);
          }
        },
        executorService);

    synchronized (pendingLeases) {
      pendingLeases.add(futureWork);
    }

    futureWork.addListener(
        new Runnable() {
          @Override
          public void run() {
            try {
              returnParser(
                  cell,
                  obtainedParser.get(0, TimeUnit.MILLISECONDS));
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
              LOG.error(e);
              Throwables.propagate(e);
            } finally {
              synchronized (pendingLeases) {
                pendingLeases.remove(futureWork);
              }
            }
          }
        },
        executorService);

    return futureWork;
  }

  private synchronized ListenableFuture<P> obtainParser(Cell cell) {
    Deque<P> parserQueue = parkedParsers.get(cell);
    if (parserQueue != null && !parserQueue.isEmpty()) {
      P parser = Preconditions.checkNotNull(parserQueue.pop());
      return Futures.immediateFuture(parser);
    }
    Optional<P> possiblyCreated = createIfAllowed(cell);
    if (possiblyCreated.isPresent()) {
      return Futures.immediateFuture(possiblyCreated.get());
    }
    SettableFuture<P> parserFututre = SettableFuture.create();
    Deque<SettableFuture<P>> requestsQueue = parserRequests.get(cell);
    if (requestsQueue == null) {
      requestsQueue = new ArrayDeque<>();
      parserRequests.put(cell, requestsQueue);
    }
    requestsQueue.add(parserFututre);
    return parserFututre;
  }

  private synchronized void returnParser(Cell cell, P parser) {
    if (parserRequests.containsKey(cell)) {
      SettableFuture<P> nextRequest = parserRequests.get(cell).pollFirst();
      if (nextRequest != null) {
        boolean wasSet = nextRequest.set(parser);
        Preconditions.checkState(wasSet, "Parser requests can't be cancelled.");
        return;
      }
    }
    Deque<P> parkedParsersQueue = parkedParsers.get(cell);
    if (parkedParsersQueue == null) {
      parkedParsersQueue = new ArrayDeque<>();
      parkedParsers.put(cell, parkedParsersQueue);
    }
    parkedParsersQueue.push(parser);
  }

  private synchronized Optional<P> createIfAllowed(Cell cell) {
    if (createdParsers.get(cell).size() >= maxParsersPerCell) {
      return Optional.absent();
    }
    P parser = parserFactory.apply(cell);
    createdParsers.put(cell, parser);
    return Optional.of(parser);
  }

  @Override
  public void close() throws Exception {
    Preconditions.checkState(!closing.get());
    closing.set(true);
    ListenableFuture<List<Object>> closeFuture;
    synchronized (pendingLeases) {
      closeFuture = Futures.successfulAsList(pendingLeases);
    }
    closeFuture.get();
    for (Map.Entry<Cell, Deque<SettableFuture<P>>> cellDequeEntry :
        parserRequests.entrySet()) {
      Preconditions.checkState(
          cellDequeEntry.getValue().isEmpty(),
          "Error shutting down ParserLeaseVendor: enqueued parser requests would cause deadlock.");
    }
    for (P parser : createdParsers.values()) {
      parser.close();
    }
  }
}
