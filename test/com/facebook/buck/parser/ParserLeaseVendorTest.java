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

import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.Cell;
import com.facebook.buck.util.concurrent.ExplicitRunExecutorService;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ParserLeaseVendorTest {

  private static final AsyncFunction<ParserForTest, Void> IMMEDIATE_NULL_ASYNC_FUNCTION =
      new AsyncFunction<ParserForTest, Void>() {
        @Override
        public ListenableFuture<Void> apply(ParserForTest input) throws Exception {
          Preconditions.checkNotNull(input);
          return Futures.immediateFuture(null);
        }
      };

  private int getHowManyParserInstancesAreCreated(
      ListeningExecutorService executorService,
      int maxParsers,
      int numRequests) throws Exception {
    final AtomicInteger createCount = new AtomicInteger(0);
    Cell cell = EasyMock.createMock(Cell.class);

    try (ParserLeaseVendor<ParserForTest> vendor =
        new ParserLeaseVendor<>(
            maxParsers,
            new Function<Cell, ParserForTest>() {
              @Override
              public ParserForTest apply(Cell input) {
                createCount.incrementAndGet();
                return new ParserForTest();
              }
            }
        )) {

      for (int i = 0; i < numRequests; ++i) {
        vendor.leaseParser(
            cell,
            IMMEDIATE_NULL_ASYNC_FUNCTION,
            executorService);
      }

      executorService.shutdown();
      return createCount.get();
    }
  }

  @Test
  public void createsConstrainedNumberOfParsers() throws Exception {
    ListeningExecutorService noopExecutor = new ExplicitRunExecutorService();
    assertThat(getHowManyParserInstancesAreCreated(noopExecutor, 2, 3), Matchers.is(2));
  }

  @Test
  public void doesntCreateParsersWhenNotNecessary() throws Exception {
    ListeningExecutorService directExecutor = MoreExecutors.newDirectExecutorService();
    // The direct executor will do the "parsing" as the lease is obtained and therefore we should
    // never need more than one to be created.
    assertThat(getHowManyParserInstancesAreCreated(directExecutor, 2, 3), Matchers.is(1));
  }

  @Test
  public void closesCreatedParsers() throws Exception {
    final int parsersCount = 4;
    final AtomicInteger parserCount = new AtomicInteger(0);
    Cell cell = EasyMock.createMock(Cell.class);
    ExplicitRunExecutorService explicitRunExecutorService = new ExplicitRunExecutorService();

    ParserLeaseVendor<ParserForTest> vendor =
        new ParserLeaseVendor<>(
            parsersCount,
            new Function<Cell, ParserForTest>() {
              @Override
              public ParserForTest apply(Cell input) {
                parserCount.incrementAndGet();
                return new ParserForTest() {
                  @Override
                  public void close() {
                    parserCount.decrementAndGet();
                  }
                };
              }
            }
        );

    for (int i = 0; i < parsersCount * 2; ++i) {
      vendor.leaseParser(
          cell,
          IMMEDIATE_NULL_ASYNC_FUNCTION,
          explicitRunExecutorService);
    }

    assertThat(parserCount.get(), Matchers.is(4));
    explicitRunExecutorService.run();
    vendor.close();
    assertThat(parserCount.get(), Matchers.is(0));
  }

  @Test
  public void fuzzForConcurrentAccess() throws Exception {
    final int parsersCount = 3;
    Cell cell = EasyMock.createMock(Cell.class);
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(4));

    try (ParserLeaseVendor<ParserForTest> vendor =
            new ParserLeaseVendor<>(
                parsersCount,
                new Function<Cell, ParserForTest>() {
                  @Override
                  public ParserForTest apply(Cell input) {
                    return new ParserForTest();
                  }
                })) {

      ImmutableSet.Builder<ListenableFuture<?>> futures = ImmutableSet.builder();
      for (int i = 0; i < 42; ++i) {
        futures.add(
            vendor.leaseParser(
                cell,
                new AsyncFunction<ParserForTest, Void>() {
                  @Override
                  public ListenableFuture<Void> apply(ParserForTest input) throws Exception {
                    Preconditions.checkNotNull(input);
                    input.pretendToDoWorkAndAssertSingleThreadedAccess();
                    return Futures.immediateFuture(null);
                  }
                },
                executorService));
      }
      for (ListenableFuture<?> future : futures.build()) {
        future.get(); // Make sure they all run and that we re-throw any exceptions.
      }
    } finally {
      executorService.shutdown();
    }
  }

  @Test
  public void closeWhenRunningJobs() throws Exception {
    Cell cell = EasyMock.createMock(Cell.class);
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(1));

    try (ParserLeaseVendor<ParserForTest> vendor =
             new ParserLeaseVendor<>(
                 /* maxParsers */ 1,
                 new Function<Cell, ParserForTest>() {
                   @Override
                   public ParserForTest apply(Cell input) {
                     return new ParserForTest();
                   }
                 })) {

      for (int i = 0; i < 5; ++i) {
        vendor.leaseParser(
            cell,
            new AsyncFunction<ParserForTest, Void>() {
              @Override
              public ListenableFuture<Void> apply(ParserForTest input) throws Exception {
                Preconditions.checkNotNull(input);
                Thread.sleep(50);
                return Futures.immediateFuture(null);
              }
            },
            executorService);
      }

    } finally {
      executorService.shutdown();
    }
  }

  @Test
  public void workThatThrows() throws Exception {
    Cell cell = EasyMock.createMock(Cell.class);
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(1));

    try (ParserLeaseVendor<ParserForTest> vendor =
             new ParserLeaseVendor<>(
                 /* maxParsers */ 2,
                 new Function<Cell, ParserForTest>() {
                   @Override
                   public ParserForTest apply(Cell input) {
                     return new ParserForTest();
                   }
                 })) {

      ImmutableSet.Builder<ListenableFuture<?>> futures = ImmutableSet.builder();
      for (int i = 0; i < 5; ++i) {
        futures.add(
            vendor.leaseParser(
                cell,
                new AsyncFunction<ParserForTest, Void>() {
                  @Override
                  public ListenableFuture<Void> apply(ParserForTest input) throws Exception {
                    throw new Exception("haha!");
                  }
                },
                executorService));
      }
      Futures.successfulAsList(futures.build()).get();

      // Make sure it's still possible to do work.
      vendor.leaseParser(
          cell,
          new AsyncFunction<ParserForTest, Void>() {
            @Override
            public ListenableFuture<Void> apply(ParserForTest input) throws Exception {
              return Futures.immediateFuture(null);
            }
          },
          executorService).get();
    } finally {
      executorService.shutdown();
    }
  }

  private static class ParserForTest implements AutoCloseable {
    private AtomicInteger sleepCallCount = new AtomicInteger(0);

    public ParserForTest() {
    }

    public void pretendToDoWorkAndAssertSingleThreadedAccess() throws Exception {
      int numCalls = sleepCallCount.incrementAndGet();
      Preconditions.checkState(numCalls == 1);
      try {
        Thread.sleep(20);
      } finally {
        sleepCallCount.decrementAndGet();
      }
    }

    @Override
    public void close() {
    }
  }
}
