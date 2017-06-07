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
import com.facebook.buck.model.Pair;
import com.google.common.collect.ComparisonChain;
import java.util.Objects;

/** A {@link BuildTargetSourcePath} which resolves to the value of another SourcePath. */
public class ForwardingBuildTargetSourcePath extends BuildTargetSourcePath {
  private final SourcePath delegate;

  public ForwardingBuildTargetSourcePath(BuildTarget target, SourcePath delegate) {
    super(target);
    this.delegate = delegate;
  }

  public SourcePath getDelegate() {
    return delegate;
  }

  @Override
  public int hashCode() {
    return Objects.hash(getTarget(), delegate);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof ForwardingBuildTargetSourcePath)) {
      return false;
    }

    ForwardingBuildTargetSourcePath that = (ForwardingBuildTargetSourcePath) other;
    return getTarget().equals(that.getTarget()) && delegate.equals(that.delegate);
  }

  @Override
  public String toString() {
    return String.valueOf(new Pair<>(getTarget(), delegate));
  }

  @Override
  public int compareTo(SourcePath other) {
    if (other == this) {
      return 0;
    }

    int classComparison = compareClasses(other);
    if (classComparison != 0) {
      return classComparison;
    }

    ForwardingBuildTargetSourcePath that = (ForwardingBuildTargetSourcePath) other;

    return ComparisonChain.start()
        .compare(getTarget(), that.getTarget())
        .compare(delegate, that.delegate)
        .result();
  }
}
