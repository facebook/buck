/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.test.selectors.Nullable;
import com.google.common.base.Preconditions;

/**
 * Data class for storing the amortized time and total time of a start/finished event pair.
 *
 */
public class TimeCostEntry<T extends BuckEvent> {

  private final T startEvent;

  private long amortizedNanoTime;
  private long totalNanoTime;
  private long totalThreadUserNanoTime;
  @Nullable private T finishEvent;

  public TimeCostEntry(T startEvent) {
    this.amortizedNanoTime = 0;
    this.totalNanoTime = 0;
    this.totalThreadUserNanoTime = 0;
    this.startEvent = startEvent;
  }

  public T getFinishEvent() {
    return Preconditions.checkNotNull(finishEvent);
  }

  public void setFinishEvent(T finishEvent) {
    this.finishEvent = finishEvent;
  }

  public void incrementAmortizedNanoTime(long nanoIncrement) {
    amortizedNanoTime += nanoIncrement;
  }

  public void setTotalNanoTime(long totalNanoTime) {
    this.totalNanoTime = totalNanoTime;
  }

  public void setTotalThreadUserNanoTime(long totalThreadUserNanoTime) {
    this.totalThreadUserNanoTime = totalThreadUserNanoTime;
  }

  public T getStartEvent() {
    return startEvent;
  }

  public long getAmortizedNanoTime() {
    return amortizedNanoTime;
  }

  public long getTotalNanoTime() {
    return totalNanoTime;
  }

  public long getTotalThreadUserNanoTime() {
    return totalThreadUserNanoTime;
  }
}
