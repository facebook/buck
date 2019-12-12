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

package com.facebook.buck.core.rules.configsetting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class ConfigSettingSelectableTest {

  private static BuildTarget buildTarget(String t) {
    return ConfigurationBuildTargetFactoryForTests.newInstance(t);
  }

  private static ConstraintValue constraintValue(String t, String c) {
    return ConstraintValue.of(buildTarget(t), ConstraintSetting.of(buildTarget(c)));
  }

  @Test
  public void refinesReturnsTrueWithEmptyValues() {
    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            buildTarget("//a:b"),
            ImmutableMap.of(),
            ImmutableSet.of(
                constraintValue("//a:x", "//a:xc"), constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            buildTarget("//a:c"),
            ImmutableMap.of(),
            ImmutableSet.of(constraintValue("//a:x", "//a:xc")));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsTrueWithSameValues() {
    ImmutableMap<String, String> values = ImmutableMap.of("a", "b");

    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            buildTarget("//a:b"),
            values,
            ImmutableSet.of(
                constraintValue("//a:x", "//a:xc"), constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            buildTarget("//a:c"), values, ImmutableSet.of(constraintValue("//a:x", "//a:xc")));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsTrueWithSpecializedValues() {
    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            buildTarget("//a:b"),
            ImmutableMap.of("a", "b", "c", "d"),
            ImmutableSet.of(
                constraintValue("//a:x", "//a:xc"), constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            buildTarget("//a:c"),
            ImmutableMap.of("a", "b"),
            ImmutableSet.of(constraintValue("//a:x", "//a:xc")));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsFalseWithNonSpecializedValues() {
    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            buildTarget("//a:b"),
            ImmutableMap.of("a", "b"),
            ImmutableSet.of(
                constraintValue("//a:x", "//a:xc"), constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            buildTarget("//a:c"),
            ImmutableMap.of("a", "b", "c", "d"),
            ImmutableSet.of(constraintValue("//a:x", "//a:xc")));

    assertFalse(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsFalseWithNonSpecializedConstraints() {
    ImmutableMap<String, String> values = ImmutableMap.of("a", "b");

    ConfigSettingSelectable configSetting1 =
        new ConfigSettingSelectable(
            buildTarget("//a:b"), values, ImmutableSet.of(constraintValue("//a:x", "//a:xc")));

    ConfigSettingSelectable configSetting2 =
        new ConfigSettingSelectable(
            buildTarget("//a:c"),
            values,
            ImmutableSet.of(
                constraintValue("//a:x", "//a:xc"), constraintValue("//a:y", "//a:yc")));

    assertFalse(configSetting1.refines(configSetting2));
  }
}
