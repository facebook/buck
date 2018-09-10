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

package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.google.common.collect.ImmutableList;

/** {@code platform} rule. */
public class PlatformRule implements ConfigurationRule {

  private final BuildTarget buildTarget;
  private final String name;
  private final ImmutableList<BuildTarget> constrainValues;

  public PlatformRule(
      BuildTarget buildTarget, String name, ImmutableList<BuildTarget> constrainValues) {
    this.buildTarget = buildTarget;
    this.name = name;
    this.constrainValues = constrainValues;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public String getName() {
    return name;
  }

  public ImmutableList<BuildTarget> getConstrainValues() {
    return constrainValues;
  }
}
