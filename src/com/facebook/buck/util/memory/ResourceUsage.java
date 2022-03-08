/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.util.memory;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;

/**
 * Resource usage of a process launched by Buck, as reported by `getrusage` on systems with GNU or
 * BSD libcs.
 */
@BuckStyleValue
public interface ResourceUsage {

  /** High water mark of the resident set size (RSS) of a process, in kilobytes (KB) */
  Optional<Long> getMaxResidentSetSize();

  /** Hardware CPU instruction counter value */
  Optional<Long> getInstructionCount();

  /**
   * @param other other instance to pull values from
   * @return combined usage with values from self if set, or from other if not set
   */
  default ResourceUsage combineWith(ResourceUsage other) {
    return ImmutableResourceUsage.ofImpl(
        this.getMaxResidentSetSize().or(other::getMaxResidentSetSize),
        this.getInstructionCount().or(other::getInstructionCount));
  }
}
