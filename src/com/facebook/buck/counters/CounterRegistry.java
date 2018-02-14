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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import java.io.Closeable;
import java.util.Collection;

public interface CounterRegistry extends Closeable {
  IntegerCounter newIntegerCounter(String category, String name, ImmutableMap<String, String> tags);

  SamplingCounter newSamplingCounter(
      String category, String name, ImmutableMap<String, String> tags);

  TagSetCounter newTagSetCounter(String category, String name, ImmutableMap<String, String> tags);

  void registerCounters(Collection<Counter> counters);

  @Subscribe
  void registerCounters(AsyncCounterRegistrationEvent event);

  class AsyncCounterRegistrationEvent extends AbstractBuckEvent {

    private ImmutableCollection<Counter> counters;
    private final String countersString;

    public AsyncCounterRegistrationEvent(ImmutableList<Counter> counters) {
      super(EventKey.unique());
      this.counters = counters;
      this.countersString = Joiner.on(",").join(counters); // CSV, based on List ordering.
    }

    public ImmutableCollection<Counter> getCounters() {
      return counters;
    }

    @Override
    protected String getValueString() {
      return countersString;
    }

    @Override
    public String getEventName() {
      return "AsyncCounterRegistration";
    }
  }
}
