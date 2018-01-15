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
import java.util.Optional;
import org.immutables.value.Value;

public abstract class Counter {

  private final CounterInfo info;

  protected Counter(String category, String name, ImmutableMap<String, String> tags) {
    this.info = CounterInfo.builder().setCategory(category).setName(name).setTags(tags).build();
  }

  /**
   * If the counter has no data, returns an absent value.
   *
   * <p>Otherwise, creates a snapshot of the current internal state of the counter builder, resets
   * the counter, then returns the snapshot.
   */
  public abstract Optional<CounterSnapshot> flush();

  public String getCategory() {
    return info.getCategory();
  }

  public String getName() {
    return info.getName();
  }

  public ImmutableMap<String, String> getTags() {
    return info.getTags();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Counter counter = (Counter) o;
    return this.info.equals(counter.info);
  }

  @Override
  public int hashCode() {
    return info.hashCode();
  }

  @Override
  public String toString() {
    return info.toString();
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractCounterInfo {
    String getCategory();

    String getName();

    ImmutableMap<String, String> getTags();
  }
}
