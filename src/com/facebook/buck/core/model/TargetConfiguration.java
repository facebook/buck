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

package com.facebook.buck.core.model;

import java.util.Optional;

/** Contains configuration information attached to a configured build target. */
public abstract class TargetConfiguration implements Comparable<TargetConfiguration> {
  public abstract Optional<BuildTarget> getConfigurationTarget();

  /** Build target for rule based build target or class name otherwise. */
  @Override
  public abstract String toString();

  @Override
  public final int compareTo(TargetConfiguration that) {
    if (this.getClass() != that.getClass()) {
      return this.getClass().getName().compareTo(that.getClass().getName());
    }
    if (this instanceof RuleBasedTargetConfiguration) {
      RuleBasedTargetConfiguration thisRb = (RuleBasedTargetConfiguration) this;
      RuleBasedTargetConfiguration thatRb = (RuleBasedTargetConfiguration) that;
      return thisRb.getTargetPlatform().compareTo(thatRb.getTargetPlatform());
    } else {
      // All other configurations are singleton, so return 0 is fine.
      return 0;
    }
  }
}
