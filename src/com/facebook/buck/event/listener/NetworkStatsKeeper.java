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

import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.unit.SizeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkStatsKeeper {

  /** remote: For caches out of the local machine. */
  private final AtomicInteger remoteDownloadedArtifactsCount;

  private final AtomicLong remoteDownloadedArtifactsBytes;

  NetworkStatsKeeper() {
    remoteDownloadedArtifactsCount = new AtomicInteger(0);
    remoteDownloadedArtifactsBytes = new AtomicLong(0);
  }

  public long getRemoteDownloadedArtifactsCount() {
    return remoteDownloadedArtifactsCount.get();
  }

  public Pair<Long, SizeUnit> getRemoteDownloadedArtifactsBytes() {
    return new Pair<>(remoteDownloadedArtifactsBytes.get(), SizeUnit.BYTES);
  }

  public void incrementRemoteDownloadedArtifactsCount() {
    remoteDownloadedArtifactsCount.incrementAndGet();
  }

  public void addRemoteDownloadedArtifactsBytes(long bytes) {
    remoteDownloadedArtifactsBytes.addAndGet(bytes);
  }
}
