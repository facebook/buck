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
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.counters.CountersSnapshotEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpArtifactCacheUploadListenerTest {
  private static final int NUMBER_OF_THREADS = 42;

  private BuildId buildId;
  private FakeClock clock;
  private BuckEventBus eventBus;
  private List<CountersSnapshotEvent> events;

  @Before
  public void setUp() {
    buildId = new BuildId();
    clock = FakeClock.doNotCare();
    events = new ArrayList<>();
    eventBus = new DefaultBuckEventBus(clock, /* async */ false, buildId, 1000);
    eventBus.register(this);
  }

  @After
  public void tearDown() throws IOException {
    eventBus.close();
  }

  @Test
  public void testEventGetsSentWhenBuildIsFinishedWithoutOutstandingUploads() {
    HttpArtifactCacheUploadListener listener =
        new HttpArtifactCacheUploadListener(eventBus, NUMBER_OF_THREADS);
    HttpArtifactCacheEvent.Started started =
        ArtifactCacheTestUtils.newUploadStartedEvent(new BuildId("rabbit"));
    listener.onArtifactUploadStart(started);
    listener.onArtifactUploadFinish(ArtifactCacheTestUtils.newFinishedEvent(started, true));
    listener.onBuildFinished(createBuildFinishedEvent(2));

    Assert.assertEquals(1, events.size());
    Assert.assertEquals(1, events.get(0).getSnapshots().size());
  }

  @Test
  public void testEventGetsSentOnLastUploadAfterBuildFinished() {
    HttpArtifactCacheUploadListener listener =
        new HttpArtifactCacheUploadListener(eventBus, NUMBER_OF_THREADS);
    listener.onBuildFinished(createBuildFinishedEvent(0));
    HttpArtifactCacheEvent.Started started =
        ArtifactCacheTestUtils.newUploadStartedEvent(new BuildId("rabbit"));
    listener.onArtifactUploadStart(started);
    listener.onArtifactUploadFinish(ArtifactCacheTestUtils.newFinishedEvent(started, true));

    Assert.assertEquals(1, events.size());
    Assert.assertEquals(1, events.get(0).getSnapshots().size());
  }

  @Test
  public void testNothingGetsSentWhenThereAreNoUploads() {
    HttpArtifactCacheUploadListener listener =
        new HttpArtifactCacheUploadListener(eventBus, NUMBER_OF_THREADS);
    listener.onBuildFinished(createBuildFinishedEvent(2));

    Assert.assertEquals(0, events.size());
  }

  private BuildEvent.Finished createBuildFinishedEvent(int timeMillis) {
    BuildEvent.Started startedEvent = BuildEvent.started(new ArrayList<>());
    startedEvent.configure(timeMillis, 0, 0, 0, buildId);
    BuildEvent.Finished finishedEvent = BuildEvent.finished(startedEvent, ExitCode.SUCCESS);
    finishedEvent.configure(timeMillis, 0, 0, 0, buildId);
    return finishedEvent;
  }

  @Subscribe
  public void onCounterEvent(CountersSnapshotEvent event) {
    events.add(event);
  }
}
