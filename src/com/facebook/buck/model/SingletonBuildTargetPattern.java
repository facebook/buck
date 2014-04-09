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
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * A pattern that matches only one build target.
 */
public class SingletonBuildTargetPattern implements BuildTargetPattern {

  public static final String RESOURCE_SUFFIX = "_resources";

  public static final String PREBUILT_NATIVE_LIBS_SUFFIX = "_prebuilt_native_libs";

  private final String fullyQualifiedName;

  /**
   * @param fullyQualifiedName The fully qualified name of valid target. It is expected to
   *     match the value returned from a {@link BuildTarget#getFullyQualifiedName()} call.
   */
  public SingletonBuildTargetPattern(String fullyQualifiedName) {
    this.fullyQualifiedName = Preconditions.checkNotNull(fullyQualifiedName);
  }

  /**
   * @return true if the given target not null and has the same fullyQualifiedName,
   *     otherwise return false.
   */
  @Override
  public boolean apply(@Nullable BuildTarget target) {
    if (target == null) {
      return false;
    } else {
      return Objects.equal(this.fullyQualifiedName, target.getFullyQualifiedName());
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SingletonBuildTargetPattern)) {
      return false;
    }
    SingletonBuildTargetPattern that = (SingletonBuildTargetPattern) o;
    return Objects.equal(this.fullyQualifiedName, that.fullyQualifiedName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(fullyQualifiedName);
  }

}
