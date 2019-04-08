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
package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTargetView;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** A pattern that matches only one build target. */
@Value.Immutable(builder = false, copy = false)
@BuckStylePackageVisibleTuple
abstract class AbstractSingletonBuildTargetPattern implements BuildTargetPattern {

  protected abstract UnflavoredBuildTargetView getTarget();

  /**
   * @param fullyQualifiedName The fully qualified name of valid target. It is expected to match the
   *     value returned from a {@link BuildTarget#getFullyQualifiedName()} call.
   */
  public static SingletonBuildTargetPattern of(Path cellPath, String fullyQualifiedName) {
    int buildTarget = fullyQualifiedName.indexOf("//");
    int colon = fullyQualifiedName.lastIndexOf(':');
    return SingletonBuildTargetPattern.of(
        ImmutableUnflavoredBuildTargetView.of(
            cellPath,
            Optional.empty(),
            fullyQualifiedName.substring(buildTarget, colon),
            fullyQualifiedName.substring(colon + 1)));
  }

  /**
   * @return true if the given target not null and has the same fullyQualifiedName, otherwise return
   *     false.
   */
  @Override
  public boolean matches(BuildTarget target) {
    // No need to check the cell name.
    return this.getTarget().getCellPath().equals(target.getCellPath())
        && this.getTarget().getBaseName().equals(target.getBaseName())
        && this.getTarget().getShortName().equals(target.getShortName());
  }

  @Override
  public String getCellFreeRepresentation() {
    return getTarget().getBaseName() + ":" + getTarget().getShortName();
  }

  @Override
  public String toString() {
    return getTarget().toString();
  }
}
