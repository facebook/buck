/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.log;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Package-private utility class which holds various process-wide
 * logging state which needs to outlive when reloading logging
 * configuration.
 */
class GlobalState {

  // Utility class; do not instantiate.
  private GlobalState() { }

  /**
   * Map of (thread ID: command ID) pairs. A given command ID can be used
   * by multiple thread IDs.
   */
  public static final ConcurrentMap<Long, String> THREAD_ID_TO_COMMAND_ID =
    new ConcurrentHashMap<>();
}
