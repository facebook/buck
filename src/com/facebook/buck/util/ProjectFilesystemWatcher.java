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

package com.facebook.buck.util;

import com.facebook.buck.event.BuckEventBus;

import java.io.IOException;

/**
 * Watches a ProjectFilesystem for file changes using a given WatchService.
 * Change events are posted to a given EventBus when postEvents is called.
 * Paths in Event contexts are always relative to the project root.
 */
public interface ProjectFilesystemWatcher {

  /**
   * Processes all pending file system events. These are generally posted to an EventBus passed
   * to the ProjectFilesystemWatcher constructor. The event bus in the parameter will be used to
   * send console events in case there are warnings that need to be shown to the user.
   */
  public void postEvents(BuckEventBus buckEventBus) throws IOException, InterruptedException;
}
