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
package com.facebook.buck.event.listener;

import com.facebook.buck.distributed.build_slave.CoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.google.common.collect.ImmutableList;

/** No-op implementation of CoordinatorBuildRuleEventsPublisher */
public class NoOpCoordinatorBuildRuleEventsPublisher
    implements CoordinatorBuildRuleEventsPublisher {

  @Override
  public void updateCoordinatorBuildProgress(CoordinatorBuildProgress progress) {}

  @Override
  public void createBuildRuleStartedEvents(ImmutableList<String> startedTargets) {}

  @Override
  public void createBuildRuleCompletionEvents(ImmutableList<String> finishedTargets) {}

  @Override
  public void createMostBuildRulesCompletedEvent() {}
}
