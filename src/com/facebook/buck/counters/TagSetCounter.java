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
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class TagSetCounter extends Counter {
  private Set<String> tagSet = new HashSet<String>();

  public TagSetCounter(String category, String name, ImmutableMap<String, String> tags) {
    super(category, name, tags);
  }

  public void add(String value) {
    synchronized (this) {
      tagSet.add(value);
    }
  }

  public void addAll(Collection<String> values) {
    synchronized (this) {
      tagSet.addAll(values);
    }
  }

  @Override
  public Optional<CounterSnapshot> flush() {
    synchronized (this) {
      if (!tagSet.isEmpty()) {
        CounterSnapshot.Builder snapshot = CounterSnapshot.builderForCounter(this);
        snapshot.putAllTagSets(getName(), tagSet);
        tagSet.clear();
        return Optional.of(snapshot.build());
      } else {
        return Optional.empty();
      }
    }
  }
}
