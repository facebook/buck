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
package com.facebook.buck.core.rules.configsetting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class ConfigSettingSelectableTest {
  private final BuildTarget selectable1 = BuildTargetFactory.newInstance("//a:b");
  private final BuildTarget selectable2 = BuildTargetFactory.newInstance("//a:c");
  private final BuildTarget constraintTarget1 = BuildTargetFactory.newInstance("//a:x");
  private final BuildTarget constraintTarget2 = BuildTargetFactory.newInstance("//a:y");

  @Test
  public void refinesReturnsTrueWithEmptyValues() {
    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            selectable1, ImmutableMap.of(), ImmutableSet.of(constraintTarget1, constraintTarget2));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            selectable2, ImmutableMap.of(), ImmutableSet.of(constraintTarget1));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsTrueWithSameValues() {
    ImmutableMap<String, String> values = ImmutableMap.of("a", "b");

    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            selectable1, values, ImmutableSet.of(constraintTarget1, constraintTarget2));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(selectable2, values, ImmutableSet.of(constraintTarget1));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsTrueWithSpecializedValues() {
    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            selectable1,
            ImmutableMap.of("a", "b", "c", "d"),
            ImmutableSet.of(constraintTarget1, constraintTarget2));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            selectable2, ImmutableMap.of("a", "b"), ImmutableSet.of(constraintTarget1));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsFalseWithNonSpecializedValues() {
    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            selectable1,
            ImmutableMap.of("a", "b"),
            ImmutableSet.of(constraintTarget1, constraintTarget2));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            selectable2, ImmutableMap.of("a", "b", "c", "d"), ImmutableSet.of(constraintTarget1));

    assertFalse(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsFalseWithNonSpecializedConstraints() {
    ImmutableMap<String, String> values = ImmutableMap.of("a", "b");

    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(selectable1, values, ImmutableSet.of(constraintTarget1));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            selectable2, values, ImmutableSet.of(constraintTarget1, constraintTarget2));

    assertFalse(configSetting1.refines(configSetting2));
  }
}
