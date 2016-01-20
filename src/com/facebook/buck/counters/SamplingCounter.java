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

public class SamplingCounter extends Counter {
  private volatile long sum;
  private volatile long count;
  private volatile long min;
  private volatile long max;

  public SamplingCounter(String category, String name, ImmutableMap<String, String> tags) {
    super(category, name, tags);
    reset();
  }

  public long getMin() {
    return min;
  }

  public long getMax() {
    return max;
  }

  public long getAverage() {
    synchronized (this) {
      if (count != 0) {
        return sum / count;
      }
    }

    return 0;
  }

  public void addSample(long value) {
    synchronized (this) {
      sum += value;
      if (count == 0) {
        min = value;
        max = value;
      } else {
        min = Math.min(min, value);
        max = Math.max(max, value);
      }
      ++count;
    }
  }

  @Override
  public void reset() {
    synchronized (this) {
      sum = 0;
      count = 0;
      min = 0;
      max = 0;
    }
  }

  @Override
  public CounterSnapshot getSnapshot() {
    CounterSnapshot.Builder snapshot = newInitializedBuilder();
    synchronized (this) {
      if (hasData()) {
        snapshot.putValues(getName() + "_count", count);
        snapshot.putValues(getName() + "_avg", getAverage());
        snapshot.putValues(getName() + "_min", min);
        snapshot.putValues(getName() + "_max", max);
      }
    }
    return snapshot.build();
  }

  @Override
  public boolean hasData() {
    return count > 0;
  }

  public long getCount() {
    return count;
  }
}
