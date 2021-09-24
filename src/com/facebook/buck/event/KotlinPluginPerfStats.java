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

import javax.annotation.Nullable;

public class KotlinPluginPerfStats {

  private final String name;
  private final long totalTime;
  private final long initialAnalysis;
  private final long stubs;
  private final long annotationProcessing;
  @Nullable private final String extraData;

  public String getName() {
    return name;
  }

  public long getTotalTime() {
    return totalTime;
  }

  public long getInitialAnalysis() {
    return initialAnalysis;
  }

  public long getStubs() {
    return stubs;
  }

  public long getAnnotationProcessing() {
    return annotationProcessing;
  }

  public String getExtraData() {
    return extraData;
  }

  public KotlinPluginPerfStats(
      String name,
      long totalTime,
      long initialAnalysis,
      long stubs,
      long annotationProcessing,
      @Nullable String extraData) {
    this.name = name;
    this.totalTime = totalTime;
    this.initialAnalysis = initialAnalysis;
    this.stubs = stubs;
    this.annotationProcessing = annotationProcessing;
    this.extraData = extraData != null ? extraData.trim() : "";
  }

  @Override
  public String toString() {
    return "KotlinPluginPerfStats{"
        + "name='"
        + name
        + '\''
        + ", totalTime="
        + totalTime
        + ", initialAnalysis="
        + initialAnalysis
        + ", stubs="
        + stubs
        + ", annotationProcessing="
        + annotationProcessing
        + ", extraData='"
        + extraData
        + '\''
        + '}';
  }
}
