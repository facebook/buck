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
import static org.junit.Assert.fail;

import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.common.base.Supplier;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CloseableMemoizedSupplierTest<T extends AbstractCloseableMemoizedSupplier> {

  public abstract static class CloseableFactory<T> {
    public abstract T makeCloseable(
        Supplier<Object> supplier, ThrowingConsumer<Object, Exception> closer);
  }

  private CloseableFactory<T> closeableFactory;

  public CloseableMemoizedSupplierTest(CloseableFactory<T> closeableFactory) {
    this.closeableFactory = closeableFactory;
  }

  @Parameterized.Parameters
  public static Iterable<Object[]> params() {
    return Arrays.asList(
        new Object[][] {
          {
            new CloseableFactory<CloseableMemoizedSupplier<Object>>() {
              @Override
              public CloseableMemoizedSupplier<Object> makeCloseable(
                  Supplier<Object> supplier, ThrowingConsumer<Object, Exception> closer) {
                Consumer<Object> nonThrowingCloser =
                    toClose -> {
                      try {
                        closer.accept(toClose);
                      } catch (Exception e) {
                        fail("Unexpected Exception");
                      }
                    };
                return CloseableMemoizedSupplier.of(supplier, nonThrowingCloser);
              }
            }
          },
          {
            new CloseableFactory<ThrowingCloseableMemoizedSupplier<Object, Exception>>() {
              @Override
              public ThrowingCloseableMemoizedSupplier<Object, Exception> makeCloseable(
                  Supplier<Object> supplier, ThrowingConsumer<Object, Exception> closer) {
                return ThrowingCloseableMemoizedSupplier.of(supplier, closer);
              }
            }
          }
        });
  }

  private static class ObjectFactory {
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

    try (T wrapper = closeableFactory.makeCloseable(factory::make, toClose -> closed.set(true))) {
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

    try (T wrapper = closeableFactory.makeCloseable(factory::make, toClose -> closed.set(true))) {
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

    try (T wrapper = closeableFactory.makeCloseable(factory::make, toClose -> closed.set(true))) {
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

    try (T wrapper = closeableFactory.makeCloseable(factory::make, toClose -> closed.set(true))) {
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
  public void testThreadSafe() throws Exception {
    final int numCalls = 50;

    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch getLatch = new CountDownLatch(numCalls);
    CountDownLatch closeLatch = new CountDownLatch(numCalls);
    CountDownLatch getAfterCloseLatch = new CountDownLatch(numCalls);

    AtomicInteger creationCount = new AtomicInteger(0);
    AtomicInteger closeCount = new AtomicInteger(0);
    AtomicInteger getSuccessCount = new AtomicInteger(0);
    AtomicInteger getFailCount = new AtomicInteger(0);

    try (T closeableMemoizedSupplier =
        closeableFactory.makeCloseable(
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
              try {
                closeableMemoizedSupplier.close();
              } catch (Exception e) {
                fail("Unexpected Exception");
              }
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
