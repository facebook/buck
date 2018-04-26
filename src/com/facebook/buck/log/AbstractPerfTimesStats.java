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

package com.facebook.buck.log;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonView;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractPerfTimesStats {

  /** @return duration of the time spent in python, in milliseconds. */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getPythonTimeMs() {
    return 0L;
  }

  /** @return duration Buck spends initializing, in milliseconds. */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getInitTimeMs() {
    return 0L;
  }

  /** @return time spent between initialization and start of parsing, in milliseconds. */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getProcessingTimeMs() {
    return 0L;
  }

  /** @return time spent parsing and processing BUCK files, in milliseconds. */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getParseTimeMs() {
    return 0L;
  }

  /** @return time it takes to generate the action graph, in milliseconds. */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getActionGraphTimeMs() {
    return 0L;
  }

  /**
   * @return duration of the rule keys computation, from the start of rule key calculation to the
   *     fetching of the first artifact from the remote cache, in milliseconds.
   */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getRulekeyTimeMs() {
    return 0L;
  }

  /**
   * @return rule key computation is happening throughout the build with different types of rule
   *     keys. This measures the total time we spend calculating all different types of rule keys.
   */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getTotalRulekeyTimeMs() {
    return 0L;
  }

  /**
   * @return duration of the fetch operation, from the first fetch event to the start of the local
   *     build, in milliseconds.
   */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getFetchTimeMs() {
    return 0L;
  }

  /**
   * @return duration of the local build phase, from start of the local build to when the build
   *     completes, in milliseconds.
   */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getBuildTimeMs() {
    return 0L;
  }

  /** @return time it takes to install to a device, in milliseconds. */
  @JsonView(JsonViews.MachineReadableLog.class)
  @Value.Default
  public Long getInstallTimeMs() {
    return 0L;
  }
}
