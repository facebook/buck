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

package com.facebook.buck.core.sourcepath;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashCode;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

/** A {@link BuildTargetSourcePath} which resolves to the value of another SourcePath. */
@BuckStyleTuple
@Value.Immutable(prehash = true)
public abstract class AbstractForwardingBuildTargetSourcePath implements BuildTargetSourcePath {

  @Override
  public abstract BuildTarget getTarget();

  protected abstract SourcePath getDelegate();

  @Override
  @Value.Parameter(value = false)
  public abstract Optional<HashCode> getPrecomputedHash();

  @Override
  public int hashCode() {
    return Objects.hash(getTarget(), getDelegate());
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof AbstractForwardingBuildTargetSourcePath)) {
      return false;
    }

    AbstractForwardingBuildTargetSourcePath that = (AbstractForwardingBuildTargetSourcePath) other;
    return getTarget().equals(that.getTarget()) && getDelegate().equals(that.getDelegate());
  }

  @Override
  public String toString() {
    return toString(getDelegate());
  }

  private String toString(Object delegateRepresentation) {
    return String.valueOf(new Pair<>(getTarget(), delegateRepresentation));
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

    AbstractForwardingBuildTargetSourcePath that = (AbstractForwardingBuildTargetSourcePath) other;

    return ComparisonChain.start()
        .compare(getTarget(), that.getTarget())
        .compare(getDelegate(), that.getDelegate())
        .result();
  }

  /**
   * @return a string that is safe to use for rule key calculations, i.e. does not include absolute
   *     paths.
   */
  @Override
  public String representationForRuleKey() {
    return getDelegate() instanceof AbstractPathSourcePath
        ? toString(((AbstractPathSourcePath) getDelegate()).getRelativePath())
        : toString();
  }
}
