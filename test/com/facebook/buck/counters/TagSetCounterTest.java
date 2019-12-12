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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import org.junit.Test;

public class TagSetCounterTest {

  private static final String CATEGORY = "Counter_Category";
  private static final String NAME = "Counter_Name";
  public static final ImmutableMap<String, String> TAGS =
      ImmutableMap.of("My super Tag Key", "And the according value!");

  @Test
  public void snapshotBeforeAddIsEmpty() {
    TagSetCounter counter = createCounter();
    assertThat(counter.flush().isPresent(), is(false));
  }

  @Test
  public void snapshotAfterAddContainsValue() {
    TagSetCounter counter = createCounter();
    counter.add("value1");
    assertThat(
        counter.flush().get().getTagSets(),
        equalTo(ImmutableSetMultimap.of("Counter_Name", "value1")));
  }

  @Test
  public void snapshotAfterFlushIsEmpty() {
    TagSetCounter counter = createCounter();
    counter.add("value1");
    counter.flush();
    assertThat(counter.flush().isPresent(), is(false));
  }

  @Test
  public void snapshotWithAddSameKeyMultipleTimesIncludesAllValues() {
    TagSetCounter counter = createCounter();
    counter.add("value1");
    counter.add("value2");
    assertThat(
        counter.flush().get().getTagSets(),
        equalTo(ImmutableSetMultimap.of("Counter_Name", "value1", "Counter_Name", "value2")));
  }

  @Test
  public void snapshotWithAddAllIncludesAllValues() {
    TagSetCounter counter = createCounter();
    counter.addAll(ImmutableSet.of("value1", "value2", "value3"));
    assertThat(
        counter.flush().get().getTagSets(),
        equalTo(
            ImmutableSetMultimap.of(
                "Counter_Name", "value1", "Counter_Name", "value2", "Counter_Name", "value3")));
  }

  private TagSetCounter createCounter() {
    return new TagSetCounter(CATEGORY, NAME, TAGS);
  }
}
