/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.select;

import com.facebook.buck.core.model.BuildTarget;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A key in <code>select</code> statement.
 *
 * <p>Can be a build target or a default condition (represented by the "DEFAULT" keyword)
 */
public class SelectorKey {

  public static final String DEFAULT_KEYWORD = "DEFAULT";

  public static final SelectorKey DEFAULT = new SelectorKey();

  private @Nullable final BuildTarget buildTarget;

  public SelectorKey(BuildTarget buildTarget) {
    this.buildTarget = Objects.requireNonNull(buildTarget);
  }

  private SelectorKey() {
    this.buildTarget = null;
  }

  public boolean isDefaultKey() {
    return buildTarget == null;
  }

  public BuildTarget getBuildTarget() {
    Objects.requireNonNull(buildTarget, "Default condition has no build target");
    return buildTarget;
  }

  /** @return <code>true</code> for keys that are not the actual targets. */
  public boolean isReserved() {
    return isDefaultKey();
  }

  @Override
  public String toString() {
    return buildTarget == null ? DEFAULT_KEYWORD : buildTarget.getFullyQualifiedName();
  }
}
