/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.counters;

import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class IntegerCounterTest {

  private static final String CATEGORY = "Counter_Category";
  private static final String NAME = "Counter_Name";
  public static final ImmutableMap<String, String> TAGS =
      ImmutableMap.of("My super Tag Key", "And the according value!");

  @Test
  public void testIncrement() {
    IntegerCounter counter = createCounter();
    Assert.assertEquals(0, counter.get());
    counter.inc();
    Assert.assertEquals(1, counter.get());
    counter.inc(42);
    Assert.assertEquals(43, counter.get());
  }

  @Test
  public void testReset() {
    IntegerCounter counter = createCounter();
    Assert.assertEquals(0, counter.get());
    counter.inc(42);
    Assert.assertEquals(42, counter.get());
    counter.flush();
    Assert.assertEquals(0, counter.get());
  }

  @Test
  public void testSnapshot() {
    IntegerCounter counter = createCounter();
    counter.inc(42);
    Optional<CounterSnapshot> snapshot = counter.flush();
    Assert.assertTrue(snapshot.isPresent());
    checkSnapshot(snapshot.get(), 42);
    Assert.assertFalse(counter.flush().isPresent());
  }

  private void checkSnapshot(CounterSnapshot snapshot, long expectedValue) {
    Assert.assertEquals(1, snapshot.getValues().size());
    Assert.assertTrue(snapshot.getValues().containsKey(NAME));
    Assert.assertEquals(expectedValue, (long) snapshot.getValues().get(NAME));
    Assert.assertEquals(TAGS.size(), snapshot.getTags().size());
    Assert.assertTrue(snapshot.getTags().containsKey(TAGS.keySet().toArray()[0]));
    Assert.assertEquals(CATEGORY, snapshot.getCategory());
  }

  private IntegerCounter createCounter() {
    return new IntegerCounter(CATEGORY, NAME, TAGS);
  }
}
