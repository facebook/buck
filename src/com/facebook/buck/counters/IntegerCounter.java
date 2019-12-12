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

public class IntegerCounter extends Counter {
  private volatile long value;
  private volatile boolean hasData;

  public IntegerCounter(String category, String name, ImmutableMap<String, String> tags) {
    super(category, name, tags);
  }

  public void inc() {
    inc(1);
  }

  public void inc(long delta) {
    synchronized (this) {
      value += delta;
      hasData = true;
    }
  }

  public long get() {
    return value;
  }

  @Override
  public Optional<CounterSnapshot> flush() {
    synchronized (this) {
      if (hasData) {
        CounterSnapshot.Builder snapshot = CounterSnapshot.builderForCounter(this);
        snapshot.putValues(getName(), value);
        value = 0;
        hasData = false;
        return Optional.of(snapshot.build());
      } else {
        return Optional.empty();
      }
    }
  }
}
