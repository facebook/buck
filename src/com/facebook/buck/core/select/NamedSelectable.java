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

package com.facebook.buck.core.select;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;
import org.immutables.value.Value;

/** A pair of selectable and a build target. */
@BuckStyleValue
public abstract class NamedSelectable {
  public abstract Optional<BuildTarget> getBuildTarget();

  public abstract Selectable getSelectable();

  @Value.Check
  protected void check() {
    if (getBuildTarget().isPresent()) {
      ConfigurationBuildTargets.validateTarget(getBuildTarget().get());
    }
  }

  public static NamedSelectable of(Optional<BuildTarget> buildTarget, Selectable selectable) {
    return ImmutableNamedSelectable.ofImpl(buildTarget, selectable);
  }
}
