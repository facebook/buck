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
package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.google.common.collect.ImmutableList;

/**
 * Used by Coordinator service to signal to local client that a rule has started or finished
 * building remotely.
 */
public interface CoordinatorBuildRuleEventsPublisher {
  void updateCoordinatorBuildProgress(CoordinatorBuildProgress progress);

  void createBuildRuleStartedEvents(ImmutableList<String> startedTargets);

  void createBuildRuleCompletionEvents(ImmutableList<String> finishedTargets);

  void createBuildRuleUnlockedEvents(ImmutableList<String> unlockedTargets);

  /**
   * If not previously created, schedules an event to be sent to distributed build client to inform
   * it that most build rules have finished remotely.
   */
  void createMostBuildRulesCompletedEvent();
}
