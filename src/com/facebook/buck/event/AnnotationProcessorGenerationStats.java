/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import java.util.List;

/** Holds processor stats for the source files it generated */
public class AnnotationProcessorGenerationStats {
  final String processorName;
  final long totalSources;
  final List<Long> sourcesGenerated;

  public String getProcessorName() {
    return processorName;
  }

  public long getTotalSources() {
    return totalSources;
  }

  public List<Long> getSourcesGenerated() {
    return sourcesGenerated;
  }

  @Override
  public String toString() {
    return "AnnotationProcessorGenerationStats{"
        + "processorName='"
        + processorName
        + '\''
        + ", totalSources="
        + totalSources
        + ", sourcesGenerated="
        + sourcesGenerated
        + '}';
  }

  public AnnotationProcessorGenerationStats(
      String processorName, long totalSources, List<Long> sourcesGenerated) {
    this.processorName = processorName;
    this.totalSources = totalSources;
    this.sourcesGenerated = sourcesGenerated;
  }
}
