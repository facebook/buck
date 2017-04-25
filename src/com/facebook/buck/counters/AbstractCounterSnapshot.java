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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCounterSnapshot {
  public static CounterSnapshot.Builder builderForCounter(Counter counter) {
    CounterSnapshot.Builder builder = CounterSnapshot.builder();
    builder.setTags(counter.getTags());
    builder.setCategory(counter.getCategory());
    return builder;
  }

  abstract String getCategory();

  abstract ImmutableMap<String, String> getTags();

  abstract ImmutableSetMultimap<String, String> getTagSets();

  abstract ImmutableMap<String, Long> getValues();
}
