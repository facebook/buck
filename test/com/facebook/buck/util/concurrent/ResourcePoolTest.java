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

package com.facebook.buck.util.concurrent;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ResourcePoolTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void doesNotCreateMoreThanMaxResources() throws Exception {
    try (Fixture f = new Fixture()) {
      CountDownLatch waitTillAllThreadsAreBusy = new CountDownLatch(f.getMaxResources());
      CountDownLatch unblockAllThreads = new CountDownLatch(1);
      List<ListenableFuture<?>> futures = new ArrayList<>();
      for (int i = 0; i < f.getMaxResources() * 10; i++) {
        futures.add(
            f.getPool()
                .scheduleOperationWithResource(
                    r -> {
                      waitTillAllThreadsAreBusy.countDown();
                      unblockAllThreads.await();
                      return r;
                    },
                    f.getExecutorService()));
      }
      waitTillAllThreadsAreBusy.await();
      unblockAllThreads.countDown();

      Futures.allAsList(futures).get();
      assertThat(f.getCreatedResources().get(), equalTo(2));
    }
  }

  @Test
  public void exceptionOnResourceCreation() throws Exception {
    try (Fixture f =
        new Fixture(
            /* maxResources */ 2,
            (i) -> {
              if (i == 0) {
                throw new TestException();
              }
            },
            ResourcePool.ResourceUsageErrorPolicy.RECYCLE)) {
      List<ListenableFuture<TestResource>> results =
          Stream.of(0, 1)
              .map(i -> f.getPool().scheduleOperationWithResource(r -> r, f.getExecutorService()))
              .collect(Collectors.toList());

      assertThat(
          Futures.successfulAsList(results).get().stream().filter(r -> r != null).count(),
          equalTo(1L));
      expectedException.expectCause(Matchers.instanceOf(TestException.class));
      Futures.allAsList(results).get();
    }
  }

  @Test
  public void exceptionOnResourceUsageWithRecycleHandling() throws Exception {
    try (Fixture f = new Fixture(/* maxResources */ 1)) {
      ListeningExecutorService executorService = MoreExecutors.newDirectExecutorService();
      List<ListenableFuture<TestResource>> results =
          Stream.of(0, 1, 2)
              .map(
                  i ->
                      f.getPool()
                          .scheduleOperationWithResource(
                              r -> {
                                if (i == 1) {
                                  throw new TestException();
                                }
                                return r;
                              },
                              executorService))
              .collect(Collectors.toList());

      assertThat(f.getCreatedResources().get(), equalTo(1));
      // Only one resource in the pool, so both requests should use resource_id == 0
      assertThat(results.get(0).get().getTestResourceId(), equalTo(0));
      assertThat(results.get(2).get().getTestResourceId(), equalTo(0));
      expectedException.expectCause(Matchers.instanceOf(TestException.class));
      results.get(1).get();
    }
  }

  @Test
  public void exceptionOnResourceUsageWithRetireHandling() throws Exception {
    try (Fixture f =
        new Fixture(/* maxResources */ 1, ResourcePool.ResourceUsageErrorPolicy.RETIRE)) {
      ListeningExecutorService executorService = MoreExecutors.newDirectExecutorService();
      List<ListenableFuture<TestResource>> results =
          Stream.of(0, 1, 2)
              .map(
                  i ->
                      f.getPool()
                          .scheduleOperationWithResource(
                              r -> {
                                if (i == 1) {
                                  throw new TestException();
                                }
                                return r;
                              },
                              executorService))
              .collect(Collectors.toList());

      Futures.successfulAsList(results).get();

      assertThat(f.getCreatedResources().get(), equalTo(f.getMaxResources() + 1));
      // First request gets the first resource (id == 0), second request errors out causing the
      // resource to be retired and the third request gets a new resource (id == 1).
      assertThat(results.get(0).get().getTestResourceId(), equalTo(0));
      assertThat(results.get(2).get().getTestResourceId(), equalTo(1));
      expectedException.expectCause(Matchers.instanceOf(TestException.class));
      results.get(1).get();
    }
  }

  private static class TestResource implements AutoCloseable {
    private final int id;

    public TestResource(int id) {
      this.id = id;
    }

    public int getTestResourceId() {
      return id;
    }

    @Override
    public void close() {}
  }

  private static class TestException extends RuntimeException {}

  private static class Fixture implements AutoCloseable {
    private final AtomicInteger createdResources;
    private final int maxResources;
    private final ResourcePool<TestResource> pool;
    private final ListeningExecutorService executorService;
    private final Set<TestResource> createdResourcesSet;
    private final Set<TestResource> closedResourcesSet;

    public Fixture() {
      this(/* maxResources */ 2, ResourcePool.ResourceUsageErrorPolicy.RECYCLE);
    }

    public Fixture(int maxResources) {
      this(maxResources, ResourcePool.ResourceUsageErrorPolicy.RECYCLE);
    }

    public Fixture(int maxResources, ResourcePool.ResourceUsageErrorPolicy errorPolicy) {
      this(maxResources, (id) -> {}, errorPolicy);
    }

    public Fixture(
        int maxResources,
        Consumer<Integer> beforeResourceCreatedFunction,
        ResourcePool.ResourceUsageErrorPolicy errorPolicy) {
      this.maxResources = maxResources;
      this.createdResources = new AtomicInteger(0);
      this.createdResourcesSet = new HashSet<>();
      this.closedResourcesSet = new HashSet<>();
      this.pool =
          new ResourcePool<>(
              /* maxResources */ maxResources,
              errorPolicy,
              () -> {
                int id = createdResources.getAndIncrement();
                beforeResourceCreatedFunction.accept(id);
                TestResource testResource =
                    new TestResource(id) {
                      @Override
                      public void close() {
                        synchronized (closedResourcesSet) {
                          closedResourcesSet.add(this);
                        }
                      }
                    };
                synchronized (createdResourcesSet) {
                  createdResourcesSet.add(testResource);
                }
                return testResource;
              });
      executorService =
          MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(maxResources));
    }

    public ListeningExecutorService getExecutorService() {
      return executorService;
    }

    public AtomicInteger getCreatedResources() {
      return createdResources;
    }

    public int getMaxResources() {
      return maxResources;
    }

    public ResourcePool<TestResource> getPool() {
      return pool;
    }

    @Override
    public void close() throws Exception {
      getPool().close();
      getPool().getShutdownFullyCompleteFuture().get(1, TimeUnit.SECONDS);
      synchronized (createdResourcesSet) {
        synchronized (closedResourcesSet) {
          assertEquals("Not all resources were closed.", createdResourcesSet, closedResourcesSet);
        }
      }
    }
  }
}
