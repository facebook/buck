/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.event.external.events;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Describes a generic buck event. This type is intended to be used by external applications (like
 * the Intellij Buck plugin) to deserialize events coming from the webserver.
 */
public interface BuckEventExternalInterface {
  // Sent when an individual test has started
  String INDIVIDUAL_TEST_AWAITING_RESULTS = "AwaitingResults";
  // Sent when a test run has started
  String TEST_RUN_STARTED = "RunStarted";
  // Sent when an install has started
  String INSTALL_STARTED = "InstallStarted";
  // Sent when a build has started
  String BUILD_STARTED = "BuildStarted";
  // Sent when a build has finished
  String BUILD_FINISHED = "BuildFinished";
  // Sent when a distributed build has started
  String DIST_BUILD_STARTED = "DistBuildStarted";
  // Sent when a distributed build has finished
  String DIST_BUILD_FINISHED = "DistBuildFinished";
  // Sent when file parsing has started
  String PARSE_STARTED = "ParseStarted";
  // Sent when file parsing has finished
  String PARSE_FINISHED = "ParseFinished";
  // Sent when project generation has started
  String PROJECT_GENERATION_STARTED = "ProjectGenerationStarted";
  // Sent when project generation has finished
  String PROJECT_GENERATION_FINISHED = "ProjectGenerationFinished";
  /** @return the time at which the event has been created, in milliseconds. */
  long getTimestamp();
  /** @return the type of the event. */
  @JsonProperty("type")
  String getEventName();
}
