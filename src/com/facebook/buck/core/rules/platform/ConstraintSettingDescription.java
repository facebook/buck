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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/**
 * A description for {@code constraint_setting}.
 *
 * <p>Constraint setting define a type of a constraint that can be used to define platforms.
 *
 * <p>For example:
 *
 * <pre>
 *   constraint_setting(
 *      name = "constraint"
 *   )
 * </pre>
 */
public class ConstraintSettingDescription
    implements ConfigurationRuleDescription<ConstraintSettingArg> {

  @Override
  public Class<ConstraintSettingArg> getConstructorArgType() {
    return ConstraintSettingArg.class;
  }

  @Override
  public ConfigurationRule createConfigurationRule(
      ConfigurationRuleResolver configurationRuleResolver,
      Cell cell,
      BuildTarget buildTarget,
      ConstraintSettingArg arg) {
    return new ConstraintSettingRule(buildTarget, arg.getName());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractConstraintSettingArg {
    String getName();
  }
}
