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

package com.facebook.buck.event;

public class FileHashCacheEvent extends AbstractBuckEvent implements LeafEvent {

  private String category = "file_hash_cache_invalidation";
  private long amortizedNanoTime;
  private long totalTimeNs;
  private long dataPointsCount;

  public FileHashCacheEvent(
      String subCategory, long amortizedNanoTime, long totalTime, long dataPointsCount) {
    super(EventKey.unique());
    this.category += "." + subCategory;
    this.amortizedNanoTime = amortizedNanoTime;
    this.totalTimeNs = totalTime;
    this.dataPointsCount = dataPointsCount;
  }

  // TODO(rvitale): Make this class abstract and remove all the field used for temporary logging for
  //   the file hash cache experiment.
  public FileHashCacheEvent(EventKey eventKey) {
    super(eventKey);
  }

  @Override
  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public long getAmortizedNanoTime() {
    return amortizedNanoTime;
  }

  public long getTotalNanoTime() {
    return totalTimeNs;
  }

  public long getDataPointsCount() {
    return dataPointsCount;
  }

  @Override
  public String getEventName() {
    return "FileHashCacheInvalidation";
  }

  @Override
  protected String getValueString() {
    return getEventName() + getEventKey().toString();
  }

  public static InvalidationStarted invalidationStarted() {
    return new InvalidationStarted();
  }

  public static InvalidationFinished invalidationFinished(InvalidationStarted started) {
    return new InvalidationFinished(started);
  }

  public static class InvalidationStarted extends FileHashCacheEvent {
    public InvalidationStarted() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "FileHashCacheInvalidationStarted";
    }
  }

  public static class InvalidationFinished extends FileHashCacheEvent {

    public InvalidationFinished(InvalidationStarted started) {
      super(started.getEventKey());
    }

    @Override
    public String getEventName() {
      return "FileHashCacheInvalidationFinished";
    }
  }
}
