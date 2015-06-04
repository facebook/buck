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

package com.facebook.buck.apple.simulator;

public enum AppleSimulatorState {
    CREATING,
    SHUTDOWN,
    BOOTING,
    BOOTED,
    SHUTTING_DOWN;

    public static AppleSimulatorState fromString(String s) {
      switch (s) {
        case "Creating":
          return CREATING;
        case "Shutdown":
          return SHUTDOWN;
        case "Booting":
          return BOOTING;
        case "Booted":
          return BOOTED;
        case "Shutting Down":
          return SHUTTING_DOWN;
        default:
          throw new RuntimeException("Unrecognized simulator state: " + s);
      }
    }
}
