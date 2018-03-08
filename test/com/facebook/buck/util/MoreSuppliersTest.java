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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class MoreSuppliersTest {
  @Test
  public void weakMemoizeShouldMemoize() {
    Supplier<Object> supplier = MoreSuppliers.weakMemoize(Object::new);
    Object a = supplier.get();
    Object b = supplier.get();
    Assert.assertSame("Supplier should have cached the instance", a, b);
  }

  @Test
  public void weakMemoizeShouldRunDelegateOnlyOnceOnConcurrentAccess() throws Exception {
    final int numFetchers = 10;

    Semaphore semaphore = new Semaphore(0);

    class TestDelegate implements Supplier<Object> {
      private int timesCalled = 0;

      public int getTimesCalled() {
        return timesCalled;
      }

      @Override
      public Object get() {
        try {
          // Wait for all the fetch threads to be ready.
          semaphore.acquire(numFetchers);
          Thread.sleep(50); // Give other threads a chance to catch up.
          timesCalled++;
          return new Object();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        } finally {
          semaphore.release(numFetchers);
        }
      }
    }

    TestDelegate delegate = new TestDelegate();
    Supplier<Object> supplier = MoreSuppliers.weakMemoize(delegate);
    ExecutorService threadPool = Executors.newFixedThreadPool(numFetchers);

    try {
      ListeningExecutorService executor = MoreExecutors.listeningDecorator(threadPool);

      class Fetcher implements Callable<Object> {
        @Override
        public Object call() {
          // Signal that this particular fetcher is ready.
          semaphore.release();
          return supplier.get();
        }
      }

      ImmutableList.Builder<Callable<Object>> fetcherBuilder = ImmutableList.builder();
      for (int i = 0; i < numFetchers; i++) {
        fetcherBuilder.add(new Fetcher());
      }

      @SuppressWarnings("unchecked")
      List<ListenableFuture<Object>> futures =
          (List<ListenableFuture<Object>>) (List<?>) executor.invokeAll(fetcherBuilder.build());

      // Wait for all fetchers to finish.
      List<Object> results = Futures.allAsList(futures).get();

      Assert.assertEquals("should only have been called once", 1, delegate.getTimesCalled());
      Assert.assertThat(
          "all result items are the same", ImmutableSet.copyOf(results), Matchers.hasSize(1));

      Preconditions.checkState(
          threadPool.shutdownNow().isEmpty(), "All jobs should have completed");
      Preconditions.checkState(
          threadPool.awaitTermination(10, TimeUnit.SECONDS),
          "Thread pool should terminate in a reasonable amount of time");
    } finally {
      // In case exceptions were thrown, attempt to shut down the thread pool.
      threadPool.shutdownNow();
      threadPool.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void weakMemoizeShouldNotMemoizeSupplierThatIsAlreadyWeakMemoized() {
    Supplier<Object> supplier = MoreSuppliers.weakMemoize(Object::new);
    Supplier<Object> twiceMemoized = MoreSuppliers.weakMemoize(supplier);
    Assert.assertSame("should have just returned the same instance", supplier, twiceMemoized);
  }
}
