/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;

/**
 * A {@link BuildTargetSourcePath} which resolves to the default output of the {@link BuildRule}
 * referred to by its target.
 */
public class DefaultBuildTargetSourcePath extends BuildTargetSourcePath {

  public DefaultBuildTargetSourcePath(BuildTarget target) {
    super(target);
  }

  @Override
  public int hashCode() {
    return getTarget().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof DefaultBuildTargetSourcePath)) {
      return false;
    }

    DefaultBuildTargetSourcePath that = (DefaultBuildTargetSourcePath) other;
    return getTarget().equals(that.getTarget());
  }

  @Override
  public String toString() {
    return String.valueOf(getTarget());
  }

  @Override
  public int compareTo(SourcePath other) {
    if (this == other) {
      return 0;
    }

    int classComparison = compareClasses(other);
    if (classComparison != 0) {
      return classComparison;
    }

    DefaultBuildTargetSourcePath that = (DefaultBuildTargetSourcePath) other;
    return getTarget().compareTo(that.getTarget());
  }
}
