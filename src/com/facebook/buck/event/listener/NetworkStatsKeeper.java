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

import com.facebook.buck.event.NetworkEvent.BytesReceivedEvent;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.unit.SizeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkStatsKeeper {

  private final AtomicLong bytesDownloaded;
  private final AtomicLong artifactDownloaded;

  public NetworkStatsKeeper() {
    this.bytesDownloaded = new AtomicLong(0);
    this.artifactDownloaded = new AtomicLong(0);
  }

  public void bytesReceived(BytesReceivedEvent bytesReceivedEvent) {
    bytesDownloaded.getAndAdd(bytesReceivedEvent.getBytesReceived());
  }

  public Pair<Long, SizeUnit> getBytesDownloaded() {
    return new Pair<>(bytesDownloaded.get(), SizeUnit.BYTES);
  }

  public long getDownloadedArtifactDownloaded() {
    return artifactDownloaded.get();
  }

  public void artifactDownloadFinished() {
    artifactDownloaded.incrementAndGet();
  }
}
