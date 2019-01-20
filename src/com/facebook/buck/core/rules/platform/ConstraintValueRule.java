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

/** {@code constraint_value} rule. */
public class ConstraintValueRule implements ConfigurationRule {

  private final BuildTarget buildTarget;
  private final String name;
  private final BuildTarget constraintSetting;

  public ConstraintValueRule(BuildTarget buildTarget, String name, BuildTarget constraintSetting) {
    this.buildTarget = buildTarget;
    this.name = name;
    this.constraintSetting = constraintSetting;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public String getName() {
    return name;
  }

  public BuildTarget getConstraintSetting() {
    return constraintSetting;
  }
}
