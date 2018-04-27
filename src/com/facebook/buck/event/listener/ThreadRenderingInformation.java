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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.test.event.TestStatusMessageEvent;
import com.facebook.buck.core.test.event.TestSummaryEvent;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.LeafEvent;
import java.util.Optional;

class ThreadRenderingInformation {
  private final Optional<BuildTarget> buildTarget;
  private final Optional<? extends AbstractBuckEvent> startEvent;
  private final Optional<? extends TestSummaryEvent> testSummary;
  private final Optional<? extends TestStatusMessageEvent> testStatusMessage;
  private final Optional<? extends LeafEvent> runningStep;
  private final long elapsedTimeMs;

  public ThreadRenderingInformation(
      Optional<BuildTarget> buildTarget,
      Optional<? extends AbstractBuckEvent> startEvent,
      Optional<? extends TestSummaryEvent> testSummary,
      Optional<? extends TestStatusMessageEvent> testStatusMessage,
      Optional<? extends LeafEvent> runningStep,
      long elapsedTimeMs) {
    this.buildTarget = buildTarget;
    this.startEvent = startEvent;
    this.testSummary = testSummary;
    this.testStatusMessage = testStatusMessage;
    this.runningStep = runningStep;
    this.elapsedTimeMs = elapsedTimeMs;
  }

  public Optional<BuildTarget> getBuildTarget() {
    return buildTarget;
  }

  public Optional<? extends AbstractBuckEvent> getStartEvent() {
    return startEvent;
  }

  public Optional<? extends TestSummaryEvent> getTestSummary() {
    return testSummary;
  }

  public Optional<? extends TestStatusMessageEvent> getTestStatusMessage() {
    return testStatusMessage;
  }

  public Optional<? extends LeafEvent> getRunningStep() {
    return runningStep;
  }

  public long getElapsedTimeMs() {
    return elapsedTimeMs;
  }
}
