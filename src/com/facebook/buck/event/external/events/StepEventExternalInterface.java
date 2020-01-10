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

package com.facebook.buck.event.external.events;

/**
 * Describes a step made by buck when building a target. This type is intended to be used by
 * external applications (like the Intellij Buck plugin) to deserialize events coming from the
 * webserver.
 */
public interface StepEventExternalInterface extends BuckEventExternalInterface {
  // Sent when a step has started
  String STEP_STARTED = "StepStarted";
  // Sent when a step has finished
  String STEP_FINISHED = "StepFinished";
  /** @return the step name. */
  String getShortStepName();
  /** @return the description of the step, usually the command itself. */
  String getDescription();
}
