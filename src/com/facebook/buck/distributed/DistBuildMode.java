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

package com.facebook.buck.distributed;

public enum DistBuildMode {
  // Old behaviour where one remote BuildSlave machine does the full build.
  REMOTE_BUILD,

  // The coordinator of the distributed build. Distributes the payload between all minions.
  COORDINATOR,

  // Helper to a distributed build (ie, not the coordinator).
  // Dumbly requests payload from the coordinator to build and reports back once it's finished.
  MINION,

  // Run as both coordinator and one of the minions.
  COORDINATOR_AND_MINION,
}
