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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class CounterRegistryImpl implements CounterRegistry {
  private static final Logger LOG = Logger.get(CounterRegistryImpl.class);

  private static final int FIRST_FLUSH_INTERVAL_MILLIS = 5000;
  private static final int FLUSH_INTERVAL_MILLIS = 30000;

  private final BuckEventBus eventBus;
  private final ScheduledFuture<?> flushCountersFuture;
  private final Set<Counter> counters;

  public CounterRegistryImpl(ScheduledExecutorService service, BuckEventBus eventBus) {
    this(service, eventBus, FIRST_FLUSH_INTERVAL_MILLIS, FLUSH_INTERVAL_MILLIS);
  }

  public CounterRegistryImpl(
      ScheduledExecutorService service,
      BuckEventBus eventBus,
      long firstFlushIntervalMillis,
      long flushIntervalMillis) {
    this.counters = new LinkedHashSet<>();
    this.eventBus = eventBus;
    flushCountersFuture =
        service.scheduleAtFixedRate(
            this::flushCounters,
            /* initialDelay */ firstFlushIntervalMillis,
            /* period */ flushIntervalMillis,
            /* unit */ TimeUnit.MILLISECONDS);
    eventBus.register(this);
  }

  @Override
  public IntegerCounter newIntegerCounter(
      String category, String name, ImmutableMap<String, String> tags) {
    return registerCounter(new IntegerCounter(category, name, tags));
  }

  @Override
  public SamplingCounter newSamplingCounter(
      String category, String name, ImmutableMap<String, String> tags) {
    return registerCounter(new SamplingCounter(category, name, tags));
  }

  @Override
  public TagSetCounter newTagSetCounter(
      String category, String name, ImmutableMap<String, String> tags) {
    return registerCounter(new TagSetCounter(category, name, tags));
  }

  @Override
  public void registerCounters(Collection<Counter> countersToRegister) {
    synchronized (this) {
      boolean countersChanged = counters.addAll(countersToRegister);
      if (!countersChanged) {
        LOG.warn(String.format("Duplicate counters=[%s]", countersToRegister));
      }
    }
  }

  @Override
  @Subscribe
  public void registerCounters(AsyncCounterRegistrationEvent event) {
    registerCounters(event.getCounters());
  }

  @Override
  public void close() {
    flushCountersFuture.cancel(false);
    flushCounters();
  }

  private <T extends Counter> T registerCounter(T counter) {
    synchronized (this) {
      Preconditions.checkState(counters.add(counter), "Duplicate counter=[%s]", counter);
    }

    return counter;
  }

  private void flushCounters() {
    List<Optional<CounterSnapshot>> snapshots;
    synchronized (this) {
      snapshots = Lists.newArrayListWithCapacity(counters.size());
      for (Counter counter : counters) {
        snapshots.add(counter.flush());
      }
    }

    ImmutableList<CounterSnapshot> presentSnapshots =
        snapshots.stream().flatMap(Optionals::toStream).collect(ImmutableList.toImmutableList());
    if (!presentSnapshots.isEmpty()) {
      CountersSnapshotEvent event = new CountersSnapshotEvent(presentSnapshots);
      eventBus.post(event);
    }
  }
}
