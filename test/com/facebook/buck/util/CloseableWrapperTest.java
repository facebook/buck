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
import static org.junit.Assert.fail;

import com.facebook.buck.util.function.ThrowingConsumer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CloseableWrapperTest<T extends AbstractCloseableWrapper> {

  public abstract static class CloseableFactory<T> {
    public abstract T makeCloseable(
        AtomicInteger obj, ThrowingConsumer<AtomicInteger, Exception> closer);
  }

  private CloseableFactory<T> closeableFactory;

  public CloseableWrapperTest(CloseableFactory<T> closeableFactory) {
    this.closeableFactory = closeableFactory;
  }

  @Parameterized.Parameters
  public static Iterable<Object[]> params() {
    return Arrays.asList(
        new Object[][] {
          {
            new CloseableFactory<CloseableWrapper<AtomicInteger>>() {
              @Override
              public CloseableWrapper<AtomicInteger> makeCloseable(
                  AtomicInteger obj, ThrowingConsumer<AtomicInteger, Exception> closer) {
                Consumer<AtomicInteger> nonThrowingCloser =
                    toClose -> {
                      try {
                        closer.accept(toClose);
                      } catch (Exception e) {
                        fail("Unexpected Exception");
                      }
                    };
                return CloseableWrapper.of(obj, nonThrowingCloser);
              }
            }
          },
          {
            new CloseableFactory<ThrowingCloseableWrapper<AtomicInteger, Exception>>() {
              @Override
              public ThrowingCloseableWrapper<AtomicInteger, Exception> makeCloseable(
                  AtomicInteger obj, ThrowingConsumer<AtomicInteger, Exception> closer) {
                return ThrowingCloseableWrapper.of(obj, closer);
              }
            }
          }
        });
  }

  private static void closer(AtomicInteger obj) {
    obj.incrementAndGet();
  }

  @Test
  public void testMain() throws Exception {
    AtomicInteger obj = new AtomicInteger(0);
    try (T wrapper = closeableFactory.makeCloseable(obj, CloseableWrapperTest::closer)) {
      assertEquals(obj, wrapper.get());
    } finally {
      // assert that close was called exactly once with this object
      assertEquals(1, obj.get());
    }
  }

  @Test(expected = Exception.class)
  public void testException() throws Exception {
    AtomicInteger obj = new AtomicInteger(0);
    try (T wrapper = closeableFactory.makeCloseable(obj, CloseableWrapperTest::closer)) {
      throw new Exception("exception");
    } finally {
      // close was still called despite the exception thrown
      assertEquals(1, obj.get());
    }
  }

  @Test
  public void duplicateCloseOnlyClosesOnce() throws Exception {
    AtomicInteger obj = new AtomicInteger(0);
    try (T wrapper = closeableFactory.makeCloseable(obj, CloseableWrapperTest::closer)) {
      assertEquals(obj, wrapper.get());
      wrapper.close();
    } finally {
      // assert that close was called exactly once with this object
      assertEquals(1, obj.get());
    }
  }

  @Test
  public void closesWithoutGet() throws Exception {
    AtomicInteger obj = new AtomicInteger(0);
    try (T wrapper = closeableFactory.makeCloseable(obj, CloseableWrapperTest::closer)) {
    } finally {
      // assert that close was called exactly once with this object
      assertEquals(1, obj.get());
    }
  }
}
