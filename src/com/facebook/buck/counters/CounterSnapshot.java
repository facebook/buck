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

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;

@BuckStyleValueWithBuilder
public abstract class CounterSnapshot {
  public static Builder builderForCounter(Counter counter) {
    Builder builder = builder();
    builder.setTags(counter.getTags());
    builder.setCategory(counter.getCategory());
    return builder;
  }

  public abstract String getCategory();

  public abstract ImmutableMap<String, String> getTags();

  public abstract ImmutableSetMultimap<String, String> getTagSets();

  public abstract ImmutableMap<String, Long> getValues();

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableCounterSnapshot.Builder {}
}
