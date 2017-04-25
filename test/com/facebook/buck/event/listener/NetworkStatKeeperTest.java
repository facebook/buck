/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.event.NetworkEvent.BytesReceivedEvent;
import com.facebook.buck.timing.Clock;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class NetworkStatKeeperTest {
  private static final double delta = 0.001;

  @Test
  public void bytesDownloadedTest() throws InterruptedException {
    NetworkStatsKeeper networkStatsKeeper = new NetworkStatsKeeper();
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(5000));
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(3000));
    Assert.assertEquals(8000, (long) networkStatsKeeper.getBytesDownloaded().getFirst());
  }

  @Test
  public void downloadSpeedTestWhenDownloadsNotInterleaved() {
    NetworkStatsKeeper networkStatsKeeper = new NetworkStatsKeeper();
    networkStatsKeeper.stopScheduler();

    HttpArtifactCacheEvent.Started startEvent1 = createMock(HttpArtifactCacheEvent.Started.class);
    expect(startEvent1.getTimestamp()).andReturn(300L).anyTimes();
    HttpArtifactCacheEvent.Finished finishedEvent1 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent1.getTimestamp()).andReturn(600L).anyTimes();

    HttpArtifactCacheEvent.Started startEvent2 = createMock(HttpArtifactCacheEvent.Started.class);
    expect(startEvent2.getTimestamp()).andReturn(400L).anyTimes();
    HttpArtifactCacheEvent.Finished finishedEvent2 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent2.getTimestamp()).andReturn(500L).anyTimes();

    HttpArtifactCacheEvent.Started startEvent3 = createMock(HttpArtifactCacheEvent.Started.class);
    expect(startEvent3.getTimestamp()).andReturn(700L).anyTimes();
    HttpArtifactCacheEvent.Finished finishedEvent3 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent3.getTimestamp()).andReturn(900L).anyTimes();

    EasyMock.replay(
        startEvent1, startEvent2, startEvent3, finishedEvent1, finishedEvent2, finishedEvent3);

    //first interval
    networkStatsKeeper.artifactDownloadedStarted(startEvent1);
    networkStatsKeeper.artifactDownloadedStarted(startEvent2);
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(10000));
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(15000));
    networkStatsKeeper.artifactDownloadFinished(finishedEvent2);
    networkStatsKeeper.artifactDownloadFinished(finishedEvent1);
    networkStatsKeeper.calculateDownloadSpeedInLastInterval();
    Assert.assertEquals(83333.33333333333, networkStatsKeeper.getDownloadSpeed().getFirst(), delta);
    Assert.assertEquals(
        83333.33333333333, networkStatsKeeper.getAverageDownloadSpeed().getFirst(), delta);

    //second interval
    networkStatsKeeper.artifactDownloadedStarted(startEvent3);
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(10000));
    networkStatsKeeper.artifactDownloadFinished(finishedEvent3);
    networkStatsKeeper.calculateDownloadSpeedInLastInterval();
    Assert.assertEquals(50000.0, networkStatsKeeper.getDownloadSpeed().getFirst(), delta);
    Assert.assertEquals(70000.0, networkStatsKeeper.getAverageDownloadSpeed().getFirst(), delta);
  }

  @Test
  public void calculateDownloadSpeedWhenInterleaved() {
    NetworkStatsKeeper networkStatsKeeper = new NetworkStatsKeeper();
    networkStatsKeeper.stopScheduler();

    HttpArtifactCacheEvent.Started startEvent1 = createMock(HttpArtifactCacheEvent.Started.class);
    expect(startEvent1.getTimestamp()).andReturn(300L).anyTimes();
    HttpArtifactCacheEvent.Finished finishedEvent1 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent1.getTimestamp()).andReturn(700L).anyTimes();

    HttpArtifactCacheEvent.Started startEvent2 = createMock(HttpArtifactCacheEvent.Started.class);
    expect(startEvent2.getTimestamp()).andReturn(400L).anyTimes();
    HttpArtifactCacheEvent.Finished finishedEvent2 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent2.getTimestamp()).andReturn(500L).anyTimes();

    HttpArtifactCacheEvent.Started startEvent3 = createMock(HttpArtifactCacheEvent.Started.class);
    expect(startEvent3.getTimestamp()).andReturn(800L).anyTimes();
    HttpArtifactCacheEvent.Finished finishedEvent3 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent3.getTimestamp()).andReturn(1100L).anyTimes();

    Clock clock = createMock(Clock.class);
    expect(clock.currentTimeMillis()).andReturn(600L);

    EasyMock.replay(
        startEvent1,
        startEvent2,
        startEvent3,
        finishedEvent1,
        finishedEvent2,
        finishedEvent3,
        clock);
    networkStatsKeeper.setClock(clock);

    networkStatsKeeper.artifactDownloadedStarted(startEvent1);
    networkStatsKeeper.artifactDownloadedStarted(startEvent2);
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(10000));
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(15000));
    networkStatsKeeper.artifactDownloadFinished(finishedEvent2);
    networkStatsKeeper.calculateDownloadSpeedInLastInterval();
    Assert.assertEquals(83333.33333333333, networkStatsKeeper.getDownloadSpeed().getFirst(), delta);

    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(10000));
    networkStatsKeeper.artifactDownloadFinished(finishedEvent1);
    networkStatsKeeper.artifactDownloadedStarted(startEvent3);
    networkStatsKeeper.bytesReceived(new BytesReceivedEvent(5000));
    networkStatsKeeper.artifactDownloadFinished(finishedEvent3);
    networkStatsKeeper.calculateDownloadSpeedInLastInterval();
    Assert.assertEquals(37500.0, networkStatsKeeper.getDownloadSpeed().getFirst(), delta);
    Assert.assertEquals(
        57142.857142857145, networkStatsKeeper.getAverageDownloadSpeed().getFirst(), delta);
  }

  @Test
  public void artifactDownloadCountTest() {
    NetworkStatsKeeper networkStatsKeeper = new NetworkStatsKeeper();
    HttpArtifactCacheEvent.Finished finishedEvent1 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent1.getTimestamp()).andReturn(400L);

    HttpArtifactCacheEvent.Finished finishedEvent2 =
        createMock(HttpArtifactCacheEvent.Finished.class);
    expect(finishedEvent2.getTimestamp()).andReturn(400L);

    networkStatsKeeper.artifactDownloadFinished(finishedEvent1);
    networkStatsKeeper.artifactDownloadFinished(finishedEvent2);

    Assert.assertEquals(2, networkStatsKeeper.getDownloadedArtifactDownloaded());
  }
}
