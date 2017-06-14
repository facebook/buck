/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.distributed;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FileMaterializationStatsTracker {
  private AtomicInteger filesMaterializedLocallyCount = new AtomicInteger(0);
  private AtomicInteger filesMaterializedRemotelyCount = new AtomicInteger(0);

  // Note: this is the total time spent by all threads; multiple threads can be downloading
  // files from the CAS at the same time.
  private AtomicLong totalTimeSpentMaterializingFilesRemotelyMillis = new AtomicLong(0);

  public void recordLocalFileMaterialized() {
    filesMaterializedLocallyCount.incrementAndGet();
  }

  public void recordRemoteFileMaterialized(long elapsedMillis) {
    filesMaterializedRemotelyCount.incrementAndGet();
    totalTimeSpentMaterializingFilesRemotelyMillis.addAndGet(elapsedMillis);
  }

  public int getFilesMaterializedLocallyCount() {
    return filesMaterializedLocallyCount.get();
  }

  public int getFilesMaterializedRemotelyCount() {
    return filesMaterializedRemotelyCount.get();
  }

  public int getTotalFilesMaterializedCount() {
    return getFilesMaterializedLocallyCount() + getFilesMaterializedRemotelyCount();
  }

  public long getTotalTimeSpentMaterializingFilesRemotelyMillis() {
    return totalTimeSpentMaterializingFilesRemotelyMillis.get();
  }

  public FileMaterializationStats getFileMaterializationStats() {
    return FileMaterializationStats.builder()
        .setFilesMaterializedLocallyCount(getFilesMaterializedLocallyCount())
        .setFilesMaterializedRemotelyCount(getFilesMaterializedRemotelyCount())
        .setTotalTimeSpentMaterializingFilesRemotelyMillis(
            getTotalTimeSpentMaterializingFilesRemotelyMillis())
        .build();
  }
}
