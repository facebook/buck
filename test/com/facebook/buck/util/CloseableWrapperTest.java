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

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CloseableWrapperTest {

  private static void closer(AtomicInteger obj) {
    obj.incrementAndGet();
  }

  @Test
  public void testMain() {
    AtomicInteger obj = new AtomicInteger(0);
    try (CloseableWrapper<AtomicInteger> wrapper =
        CloseableWrapper.of(obj, CloseableWrapperTest::closer)) {
      assertEquals(0, wrapper.get().get());
    } finally {
      // assert that close was called exactly once with this object
      assertEquals(1, obj.get());
    }
  }

  @Test(expected = Exception.class)
  public void testException() throws Exception {
    AtomicInteger obj = new AtomicInteger(0);
    try (CloseableWrapper<AtomicInteger> wrapper =
        CloseableWrapper.of(obj, CloseableWrapperTest::closer)) {
      throw new Exception("exception");
    } finally {
      // close was still called despite the exception thrown
      assertEquals(1, obj.get());
    }
  }

  @Test
  public void duplicateCloseOnlyClosesOnce() {
    AtomicInteger obj = new AtomicInteger(0);
    try (CloseableWrapper<AtomicInteger> wrapper =
        CloseableWrapper.of(obj, CloseableWrapperTest::closer)) {
      assertEquals(0, wrapper.get().get());
      wrapper.close();
    } finally {
      // assert that close was called exactly once with this object
      assertEquals(1, obj.get());
    }
  }

  @Test
  public void closesWithoutGet() {
    AtomicInteger obj = new AtomicInteger(0);
    try (CloseableWrapper<AtomicInteger> wrapper =
        CloseableWrapper.of(obj, CloseableWrapperTest::closer)) {
    } finally {
      // assert that close was called exactly once with this object
      assertEquals(1, obj.get());
    }
  }
}
