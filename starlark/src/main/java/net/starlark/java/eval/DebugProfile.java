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

package net.starlark.java.eval;

import java.util.concurrent.atomic.AtomicInteger;

/** Hold the information about the number of debuggers or profilers attached to this process. */
class DebugProfile {

  private static final AtomicInteger numberOfAgents = new AtomicInteger(0);

  /** Add or subtract the number of agents (profilers or debuggers). */
  static void add(int delta) {
    numberOfAgents.addAndGet(delta);
  }

  /** True iff the number of agents (profilers or debuggers) is non-zero. */
  static boolean hasAny() {
    return numberOfAgents.get() != 0;
  }
}
