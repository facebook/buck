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
package com.facebook.buck.event.listener.stats.cache;

import org.junit.Assert;
import org.junit.Test;

public class NetworkStatKeeperTest {

  @Test
  public void bytesDownloadedTest() {
    NetworkStatsKeeper networkStatsKeeper = new NetworkStatsKeeper();
    networkStatsKeeper.addRemoteDownloadedArtifactsBytes(5000);
    networkStatsKeeper.addRemoteDownloadedArtifactsBytes(3000);
    Assert.assertEquals(8000, networkStatsKeeper.getRemoteDownloadStats().getBytes());
  }

  @Test
  public void artifactDownloadCountTest() {
    NetworkStatsKeeper networkStatsKeeper = new NetworkStatsKeeper();

    networkStatsKeeper.incrementRemoteDownloadedArtifactsCount();
    networkStatsKeeper.incrementRemoteDownloadedArtifactsCount();

    Assert.assertEquals(2, networkStatsKeeper.getRemoteDownloadStats().getArtifacts());
  }
}
