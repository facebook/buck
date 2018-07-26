/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.support.bgtasks;

/**
 * BackgroundTaskManager schedules and runs background bgtasks like cleanup/logging. A manager
 * should be notified when a new command starts and when it finishes so that it can schedule bgtasks
 * appropriately.
 */
public interface BackgroundTaskManager {

  /**
   * Code passed to notify(). COMMAND_START: when buck command is started COMMAND_END: when buck
   * command has finished, used to trigger background task execution
   */
  enum Notification {
    COMMAND_START,
    COMMAND_END
  }

  /**
   * Schedule a task to be run in the background.
   *
   * @param task Task object to be run
   * @param taskName Descriptive string to be used in task ID for logging
   * @return Full task ID used by task manager
   */
  String schedule(BackgroundTask<?> task, String taskName);

  /**
   * Notify the manager of some event, e.g. command start/end. Exceptions should generally be caught
   * and handled by the manager, except in test implementations.
   *
   * @param code Type of event to notify of
   */
  void notify(Notification code);
}
