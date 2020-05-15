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

package com.facebook.buck.support.criticalpath;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import java.util.Optional;

/** Immutable critical path node suitable for reporting. */
@BuckStyleValueWithBuilder
public interface ReportableCriticalPathNode {
  /** The target associated with this critical path node. */
  BuildTarget getTarget();

  /** The type of the target associated with this critical path node. */
  String getType();

  /** The total cost of the critical path up to this node. */
  long getPathCostMilliseconds();

  /** The execution time of this particular target. */
  long getExecutionTimeMilliseconds();

  /** The event timestamp for the finalization of the build target, in nanoseconds. */
  long getEventNanoTime();

  /** If applicable, the sibling target to this target with the second-longest path. */
  Optional<BuildTarget> getClosestSiblingTarget();

  /**
   * If applicable, the delta between the critical path and the second-longest path passing through
   * the sibling target, in milliseconds.
   */
  Optional<Long> getClosestSiblingExecutionTimeDelta();
}
