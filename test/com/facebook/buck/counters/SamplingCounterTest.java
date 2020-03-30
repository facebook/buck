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
import java.util.Map;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class SamplingCounterTest {

  private static final String CATEGORY = "Counter_Category";
  private static final String NAME = "Counter_Name";
  public static final ImmutableMap<String, String> TAGS =
      ImmutableMap.of("My super Tag Key", "And the according value!");

  @Test
  public void testAddSample() {
    SamplingCounter counter = createCounter();
    counter.addSample(63);
    counter.addSample(42);
    counter.addSample(21);
    Assert.assertEquals(42, counter.getAverage());
    Assert.assertEquals(21, counter.getMin());
    Assert.assertEquals(63, counter.getMax());
    Assert.assertEquals(3, counter.getCount());
  }

  @Test
  public void testReset() {
    SamplingCounter counter = createCounter();
    Assert.assertEquals(0, counter.getCount());
    counter.addSample(84);
    Assert.assertEquals(1, counter.getCount());
    counter.flush();
    Assert.assertEquals(0, counter.getCount());
  }

  @Test
  public void testSnapshot() {
    SamplingCounter counter = createCounter();
    counter.addSample(42);
    Optional<CounterSnapshot> snapshot = counter.flush();
    Assert.assertTrue(snapshot.isPresent());
    checkSnapshot(snapshot.get(), 42);
    counter.addSample(21);
    Optional<CounterSnapshot> snapshot2 = counter.flush();
    Assert.assertTrue(snapshot2.isPresent());
    checkSnapshot(snapshot2.get(), 21);
  }

  @Test
  public void testSnapshotWithoutSamples() {
    SamplingCounter counter = createCounter();
    Optional<CounterSnapshot> snapshot = counter.flush();
    Assert.assertFalse(snapshot.isPresent());
  }

  private void checkSnapshot(CounterSnapshot snapshot, long expectedValue) {
    Assert.assertEquals(4, snapshot.getValues().size());
    boolean atLeastOneExpectedValueFound = false;
    for (Map.Entry<String, Long> entry : snapshot.getValues().entrySet()) {
      Assert.assertTrue(entry.getKey().startsWith(NAME));
      Assert.assertNotEquals(0, (long) entry.getValue());
      if (expectedValue == entry.getValue()) {
        atLeastOneExpectedValueFound = true;
      }
    }
    Assert.assertTrue(atLeastOneExpectedValueFound);
    Assert.assertEquals(TAGS.size(), snapshot.getTags().size());
    Assert.assertTrue(snapshot.getTags().containsKey(TAGS.keySet().toArray()[0]));
    Assert.assertEquals(CATEGORY, snapshot.getCategory());
  }

  private SamplingCounter createCounter() {
    return new SamplingCounter(CATEGORY, NAME, TAGS);
  }
}
