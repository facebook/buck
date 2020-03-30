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
 * Describes the progress made by buck with the operations described below. This type is intended to
 * be used by external applications (like the Intellij Buck plugin) to deserialize events coming
 * from the webserver.
 */
public interface ProgressEventInterface extends BuckEventExternalInterface {
  // Sent when we make parsing progress
  String PARSING_PROGRESS_UPDATED = "ParsingProgressUpdated";
  // Sent when we make project generation progress
  String PROJECT_GENERATION_PROGRESS_UPDATED = "ProjectGenerationProgressUpdated";
  // Sent when we make build progress
  String BUILD_PROGRESS_UPDATED = "BuildProgressUpdated";
  /** @return the current progress value for any of the build, parse or project generation events */
  double getProgressValue();
}
