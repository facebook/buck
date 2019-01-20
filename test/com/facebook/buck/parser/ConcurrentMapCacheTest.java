/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import org.hamcrest.Matchers;
import org.junit.Test;

public class ConcurrentMapCacheTest {

  @Test
  public void shouldAllowAnEntryToBeAdded() {
    ConcurrentMapCache<String, Integer> cache = new ConcurrentMapCache<>(1);
    cache.putIfAbsentAndGet("cake", 1);

    Integer value = cache.getIfPresent("cake");

    assertEquals(1, value.intValue());
  }

  @Test
  public void shouldRemoveValues() {
    ConcurrentMapCache<String, Integer> cache = new ConcurrentMapCache<>(1);
    cache.putIfAbsentAndGet("cake", 1);

    cache.invalidate("cake");

    assertNull(cache.getIfPresent("cake"));
  }

  @Test
  public void shouldOnlyAddAnItemOnceToTheCache() {
    ConcurrentMapCache<String, WeirdInt> cache = new ConcurrentMapCache<>(1);
    WeirdInt value = new WeirdInt(42);
    WeirdInt value2 = new WeirdInt(42);

    cache.putIfAbsentAndGet("cake", value);
    cache.putIfAbsentAndGet("cake", value2);

    assertThat(cache.getIfPresent("cake"), Matchers.is(value));
  }

  @Test(expected = NullPointerException.class)
  public void disallowsNullValuesInTheCache() {
    ConcurrentMapCache<String, Integer> cache = new ConcurrentMapCache<>(1);
    cache.putIfAbsentAndGet("cake", null);
  }

  private static class WeirdInt {
    private int value;

    public WeirdInt(int value) {
      this.value = value;
    }

    @Override
    public int hashCode() {
      return value;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof WeirdInt)) {
        return false;
      }
      return ((WeirdInt) obj).value == value;
    }
  }
}
