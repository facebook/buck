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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.counters.CountersSnapshotEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.timing.FakeClock;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class HttpArtifactCacheUploadListenerTest {
  private static final int NUMBER_OF_THREADS = 42;

  private BuildId buildId;
  private FakeClock clock;
  private BuckEventBus eventBus;
  private List<CountersSnapshotEvent> events;
  private HttpArtifactCacheEvent.Started lastStartedEvent;

  @Before
  public void setUp() {
    buildId = new BuildId();
    clock = new FakeClock(0);
    events = Lists.newArrayList();
    eventBus = new BuckEventBus(clock, buildId);
    eventBus = new BuckEventBus(clock, /* async */ false, buildId, 1000);
    eventBus.register(this);
    lastStartedEvent = null;
  }

  @After
  public void tearDown() throws IOException {
    eventBus.close();
  }

  @Test
  public void testEventGetsSentWhenBuildIsFinishedWithoutOutstandingUploads() throws IOException {
    HttpArtifactCacheUploadListener listener = new HttpArtifactCacheUploadListener(
        eventBus, NUMBER_OF_THREADS);
    listener.onArtifactUploadStart(createUploadStartedEvent(0));
    listener.onArtifactUploadFinish(createUploadFinishedEvent(1));
    listener.onBuildFinished(createBuildFinishedEvent(2));

    Assert.assertEquals(1, events.size());
    Assert.assertEquals(1, events.get(0).getSnapshots().size());
  }

  @Test
  public void testEventGetsSentOnLastUploadAfterBuildFinished() throws IOException {
    HttpArtifactCacheUploadListener listener = new HttpArtifactCacheUploadListener(
        eventBus, NUMBER_OF_THREADS);
    listener.onBuildFinished(createBuildFinishedEvent(0));
    listener.onArtifactUploadStart(createUploadStartedEvent(1));
    listener.onArtifactUploadFinish(createUploadFinishedEvent(2));

    Assert.assertEquals(1, events.size());
    Assert.assertEquals(1, events.get(0).getSnapshots().size());
  }

  @Test
  public void testNothingGetsSentWhenThereAreNoUploads() throws IOException {
    HttpArtifactCacheUploadListener listener = new HttpArtifactCacheUploadListener(
        eventBus, NUMBER_OF_THREADS);
    listener.onBuildFinished(createBuildFinishedEvent(2));

    Assert.assertEquals(0, events.size());
  }

  private BuildEvent.Finished createBuildFinishedEvent(int timeMillis) {
    BuildEvent.Started startedEvent = BuildEvent.started(Lists.<String>newArrayList());
    startedEvent.configure(timeMillis, 0, 0, buildId);
    BuildEvent.Finished finishedEvent = BuildEvent.finished(startedEvent, 0);
    finishedEvent.configure(timeMillis, 0, 0, buildId);
    return finishedEvent;
  }

  private HttpArtifactCacheEvent.Finished createUploadFinishedEvent(
      int timeMillis) {
    HttpArtifactCacheEvent.Finished event =
        HttpArtifactCacheEvent.newFinishedEventBuilder(lastStartedEvent).build();
    event.configure(timeMillis, 0, 0, buildId);
    return event;
  }

  private HttpArtifactCacheEvent.Started createUploadStartedEvent(int timeMillis) {
    final HttpArtifactCacheEvent.Scheduled scheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.<String>absent(), ImmutableSet.<RuleKey>of());
    lastStartedEvent = HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
    lastStartedEvent.configure(timeMillis, 0, 0, buildId);
    return lastStartedEvent;
  }

  @Subscribe
  public void onCounterEvent(CountersSnapshotEvent event) {
    events.add(event);
  }
}
