/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

public class CloseableMemoizedSupplierTest {

  private class ObjectFactory {
    private AtomicInteger creationCount = new AtomicInteger(0);

    public int getCreationCount() {
      return creationCount.get();
    }

    public Object make() {
      creationCount.incrementAndGet();
      return new Object();
    }
  }

  @Test
  public void testCloseWithNoCreatedObject() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);

    ObjectFactory factory = new ObjectFactory();

    try (CloseableMemoizedSupplier<Object, Exception> wrapper =
        CloseableMemoizedSupplier.of(factory::make, toClose -> closed.set(true))) {
      // do not get from supplier
    }

    // assert that close was not called and no objects were ever created
    assertFalse(closed.get());
    assertEquals(0, factory.getCreationCount());
  }

  @Test
  public void testCloseWithCreatedObject() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);

    ObjectFactory factory = new ObjectFactory();

    try (CloseableMemoizedSupplier<Object, Exception> wrapper =
        CloseableMemoizedSupplier.of(factory::make, toClose -> closed.set(true))) {
      wrapper.get();
      wrapper.get(); // multiple gets should still only trigger one object creation
    }
    // assert that close was called and no objects were ever created
    assertTrue(closed.get());
    assertEquals(1, factory.getCreationCount());
  }

  @Test(expected = Exception.class)
  public void testExceptionStillClose() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);

    ObjectFactory factory = new ObjectFactory();

    try (CloseableMemoizedSupplier<Object, Exception> wrapper =
        CloseableMemoizedSupplier.of(factory::make, toClose -> closed.set(true))) {
      wrapper.get();
      throw new Exception();
    } finally {
      // assert that close was called and no objects were ever created
      assertTrue(closed.get());
      assertEquals(1, factory.getCreationCount());
    }
  }

  @Test(expected = IllegalStateException.class)
  public void testGetAfterClose() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);

    ObjectFactory factory = new ObjectFactory();

    try (CloseableMemoizedSupplier<Object, Exception> wrapper =
        CloseableMemoizedSupplier.of(factory::make, toClose -> closed.set(true))) {
      wrapper.get();
      wrapper.close();
      wrapper.get();
    } finally {
      // assert that close was called and no objects were ever created
      assertTrue(closed.get());
      assertEquals(1, factory.getCreationCount());
    }
  }

  @Test(timeout = 5000)
  public void testThreadSafe() {
    final int numCalls = 50;

    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch getLatch = new CountDownLatch(numCalls);
    CountDownLatch closeLatch = new CountDownLatch(numCalls);
    CountDownLatch getAfterCloseLatch = new CountDownLatch(numCalls);

    AtomicInteger creationCount = new AtomicInteger(0);
    AtomicInteger closeCount = new AtomicInteger(0);
    AtomicInteger getSuccessCount = new AtomicInteger(0);
    AtomicInteger getFailCount = new AtomicInteger(0);

    try (CloseableMemoizedSupplier<Object, RuntimeException> closeableMemoizedSupplier =
        CloseableMemoizedSupplier.of(
            () -> {
              creationCount.incrementAndGet();
              return new Object();
            },
            ignored -> closeCount.incrementAndGet())) {

      for (int i = 0; i < numCalls; i++) {
        executor.execute(
            () -> {
              closeableMemoizedSupplier.get();
              getSuccessCount.incrementAndGet();
              getLatch.countDown();
            });
      }

      for (int i = 0; i < numCalls; i++) {
        executor.execute(
            () -> {
              try {
                getLatch.await();
              } catch (InterruptedException e) {
                Assume.assumeTrue("Test Interrupted", false);
              }
              closeableMemoizedSupplier.close();
              closeLatch.countDown();
            });
      }

      for (int i = 0; i < numCalls; i++) {
        executor.execute(
            () -> {
              try {
                closeLatch.await();
                closeableMemoizedSupplier.get();
              } catch (InterruptedException e) {
                Assume.assumeTrue("Test Interrupted", false);
              } catch (IllegalStateException e) {
                getFailCount.incrementAndGet();
              }

              getAfterCloseLatch.countDown();
            });
      }

      getAfterCloseLatch.await();

      assertEquals(1, creationCount.get());
      assertEquals(1, closeCount.get());
      assertEquals(numCalls, getSuccessCount.get());
      assertEquals(numCalls, getFailCount.get());

    } catch (InterruptedException e) {
      Assume.assumeTrue("Test Interrupted", false);
    } finally {
      executor.shutdownNow();
    }
  }
}
