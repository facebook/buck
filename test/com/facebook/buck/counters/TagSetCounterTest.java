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

package com.facebook.buck.counters;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;

import org.junit.Test;

public class TagSetCounterTest {

  private static final String CATEGORY = "Counter_Category";
  private static final String NAME = "Counter_Name";
  public static final ImmutableMap<String, String> TAGS = ImmutableMap.of(
      "My super Tag Key", "And the according value!"
  );

  @Test
  public void snapshotBeforePutIsEmpty() {
    TagSetCounter counter = createCounter();
    assertThat(counter.flush().isPresent(), is(false));
  }

  @Test
  public void snapshotAfterPutContainsValue() {
    TagSetCounter counter = createCounter();
    counter.put("key1", "value1");
    assertThat(
        counter.flush().get().getTagSets(),
        equalTo(ImmutableSetMultimap.of("key1", "value1")));
  }

  @Test
  public void snapshotAfterFlushIsEmpty() {
    TagSetCounter counter = createCounter();
    counter.put("key1", "value1");
    counter.flush();
    assertThat(
        counter.flush().isPresent(),
        is(false));
  }

  @Test
  public void snapshotWithPutSameKeyMultipleTimesIncludesAllValues() {
    TagSetCounter counter = createCounter();
    counter.put("key1", "value1");
    counter.put("key1", "value2");
    assertThat(
        counter.flush().get().getTagSets(),
        equalTo(ImmutableSetMultimap.of("key1", "value1", "key1", "value2")));
  }

  @Test
  public void snapshotWithPutAllIncludesAllValues() {
    TagSetCounter counter = createCounter();
    counter.putAll(ImmutableSetMultimap.of("key1", "value1", "key1", "value2", "key2", "value3"));
    assertThat(
        counter.flush().get().getTagSets(),
        equalTo(
            ImmutableSetMultimap.of("key1", "value1", "key1", "value2", "key2", "value3")));
  }

  private TagSetCounter createCounter() {
    return new TagSetCounter(CATEGORY, NAME, TAGS);
  }
}
