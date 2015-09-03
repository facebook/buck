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

package com.facebook.buck.cli;

import com.facebook.buck.model.BuildTarget;

public class QueryBuildTarget implements QueryTarget {

  private final BuildTarget target;

  public QueryBuildTarget(BuildTarget target) {
    this.target = target;
  }

  public BuildTarget getBuildTarget() {
    return target;
  }

  @Override
  public String getKey() {
    return target.getFullyQualifiedName();
  }

  @Override
  public int compareTo(QueryTarget other) {
    return getKey().compareTo(other.getKey());
  }

  @Override
  public String toString() {
    return target.toString();
  }

  @Override
  public int hashCode() {
    return getKey().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof QueryBuildTarget) &&
        this.target.equals(((QueryBuildTarget) other).getBuildTarget());
  }
}
