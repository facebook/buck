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

package com.facebook.buck.io;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;

public class WatchEvents {

  private WatchEvents() {
    // Utility class
  }


  public static boolean isPathCreateOrDeleteEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  /**
   * @param event The event to be tested.
   * @return true if event is a path change notification.
   */
  public static boolean isPathChangeEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_MODIFY ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  /**
   *
   * @param event the event to format.
   * @return the formatted event context string.
   */
  public static String createContextString(WatchEvent<?> event) {
    if (isPathChangeEvent(event)) {
      Path path = (Path) event.context();
      return path.toAbsolutePath().normalize().toString();
    }
    return String.valueOf(event.context());
  }
}
