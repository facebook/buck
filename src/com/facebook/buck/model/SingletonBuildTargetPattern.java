/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.model;

import com.google.common.base.Objects;

import javax.annotation.Nullable;

/**
 * A pattern that matches only one build target.
 */
public class SingletonBuildTargetPattern implements BuildTargetPattern {

  private final UnflavoredBuildTarget target;

  /**
   * @param fullyQualifiedName The fully qualified name of valid target. It is expected to
   *     match the value returned from a {@link BuildTarget#getFullyQualifiedName()} call.
   */
  public SingletonBuildTargetPattern(String fullyQualifiedName) {

    int colon = fullyQualifiedName.lastIndexOf(':');
    target = UnflavoredBuildTarget
        .builder(fullyQualifiedName.substring(0, colon), fullyQualifiedName.substring(colon + 1))
        .build();
  }

  /**
   * @return true if the given target not null and has the same fullyQualifiedName,
   *     otherwise return false.
   */
  @Override
  public boolean apply(@Nullable BuildTarget target) {
    return target != null && this.target.equals(target.getUnflavoredBuildTarget());
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SingletonBuildTargetPattern)) {
      return false;
    }
    SingletonBuildTargetPattern that = (SingletonBuildTargetPattern) o;
    return Objects.equal(this.target, that.target);
  }

  @Override
  public int hashCode() {
    return target.hashCode();
  }

}
