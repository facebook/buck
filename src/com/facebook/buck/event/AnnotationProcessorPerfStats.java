/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event;

import java.util.ArrayList;
import java.util.List;

/** Holds performance stats for a single annotation processor */
public class AnnotationProcessorPerfStats {
  final String processorName;
  final long initTime;
  final long totalTime;
  final int rounds;
  final List<Long> roundTimes;

  public AnnotationProcessorPerfStats(
      String processorName, long initTime, long totalTime, List<Long> roundTimes) {
    this.processorName = processorName;
    this.totalTime = totalTime;
    this.rounds = roundTimes.size();
    this.initTime = initTime;
    this.roundTimes = roundTimes;
  }

  public String getProcessorName() {
    return processorName;
  }

  public long getInitTime() {
    return initTime;
  }

  public long getTotalTime() {
    return totalTime;
  }

  public int getRounds() {
    return rounds;
  }

  public List<Long> getRoundTimes() {
    return roundTimes;
  }

  @Override
  public String toString() {
    return "AnnotationProcessorPerfStats{"
        + "processorName='"
        + processorName
        + '\''
        + ", initTime="
        + initTime
        + ", totalTime="
        + totalTime
        + ", rounds="
        + rounds
        + ", roundTimes="
        + roundTimes
        + '}';
  }

  public static class Builder {

    private final String processorName;
    private long initTime = 0;
    private final List<Long> roundTimes = new ArrayList<>();

    public Builder(String processorName) {
      this.processorName = processorName;
    }

    public void addRoundTime(long roundTime) {
      roundTimes.add(roundTime);
    }

    public void addInitTime(long initTime) {
      this.initTime += initTime;
    }

    public AnnotationProcessorPerfStats build() {
      long totalTime = initTime;
      for (Long roundTime : roundTimes) {
        totalTime += roundTime;
      }
      return new AnnotationProcessorPerfStats(processorName, initTime, totalTime, roundTimes);
    }

    public String getProcessorName() {
      return processorName;
    }
  }
}
