/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.util.Scope;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Supplier;

/** Event Class containing the perf data regarding action graph building */
public class ActionGraphPerfStatEvent extends AbstractBuckEvent {

  private static final String EVENT_NAME = "ActionGraphPerfStats";

  private final long elapsedTime;
  private final int numberNodesGenerated;
  private final long numNoopNodesGenerated;
  private final String targetNodeDescriptionName;
  private final String buildTargetName;

  private ActionGraphPerfStatEvent(
      long time,
      int generatedNodesCount,
      long generatedNoopNodesCount,
      String descriptionName,
      String buildName) {
    super(EventKey.unique());
    elapsedTime = time;
    numberNodesGenerated = generatedNodesCount;
    numNoopNodesGenerated = generatedNoopNodesCount;
    targetNodeDescriptionName = descriptionName;
    buildTargetName = buildName;
  }

  /**
   * Creates a Scope for the timing event
   *
   * @param clock
   * @param eventBus
   * @param getRuleSize
   * @param descriptionName
   * @param buildTargetName
   * @return
   */
  public static Scope start(
      Clock clock,
      BuckEventBus eventBus,
      Supplier<Integer> getRuleSize,
      Supplier<Long> getNoOpRuleSize,
      String descriptionName,
      String buildTargetName) {
    ActionGraphPerfStatEvent.Start start = new Start(clock);
    int startSize = getRuleSize.get();
    long noopStartSize = getNoOpRuleSize.get();
    return () ->
        eventBus.post(
            start.finish(
                getRuleSize.get() - startSize,
                getNoOpRuleSize.get() - noopStartSize,
                descriptionName,
                buildTargetName));
  }

  @Override
  protected String getValueString() {
    return "";
  }

  @Override
  public String getEventName() {
    return EVENT_NAME;
  }

  public long getElapsedTime() {
    return elapsedTime;
  }

  public int getNumberNodesGenerated() {
    return numberNodesGenerated;
  }

  public long getNumNoopNodesGenerated() {
    return numNoopNodesGenerated;
  }

  public String getBuildTargetName() {
    return buildTargetName;
  }

  public String getTargetNodeDescriptionName() {
    return targetNodeDescriptionName;
  }

  /** Class representing the start of timing */
  public static class Start {

    private final long startTime;
    private Clock clock;

    private Start(Clock clock) {
      this.clock = clock;
      this.startTime = clock.currentTimeMillis();
    }

    /** Generates the event representing the end of the timing */
    private ActionGraphPerfStatEvent finish(
        int generatedNodesCount, long noopCount, String descriptionName, String buildTargetName) {
      return new ActionGraphPerfStatEvent(
          clock.currentTimeMillis() - startTime,
          generatedNodesCount,
          noopCount,
          descriptionName,
          buildTargetName);
    }
  }
}
