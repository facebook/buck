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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;

public class TagSetCounter extends Counter {
  private SetMultimap<String, String> tagSets = HashMultimap.create();

  public TagSetCounter(String category, String name, ImmutableMap<String, String> tags) {
    super(category, name, tags);
  }

  public void put(String key, String value) {
    synchronized (this) {
      tagSets.put(key, value);
    }
  }

  public void putAll(Multimap<String, String> newTagSets) {
    synchronized (this) {
      tagSets.putAll(newTagSets);
    }
  }

  @Override
  public void reset() {
    synchronized (this) {
      tagSets.clear();
    }
  }

  @Override
  public CounterSnapshot getSnapshot() {
    CounterSnapshot.Builder snapshot = newInitializedBuilder();
    synchronized (this) {
      snapshot.putAllTagSets(tagSets);
    }
    return snapshot.build();
  }

  @Override
  public boolean hasData() {
    synchronized (this) {
      return !tagSets.isEmpty();
    }
  }
}
