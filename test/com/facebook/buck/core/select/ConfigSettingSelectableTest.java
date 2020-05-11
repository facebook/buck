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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class ConfigSettingSelectableTest {

  @Test
  public void refinesReturnsTrueWithEmptyValues() {
    ConfigSettingSelectable configSetting1 =
        ConfigSettingSelectable.of(
            ImmutableMap.of(),
            ImmutableSet.of(
                TestSelectables.constraintValue("//a:x", "//a:xc"),
                TestSelectables.constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        ConfigSettingSelectable.of(
            ImmutableMap.of(), ImmutableSet.of(TestSelectables.constraintValue("//a:x", "//a:xc")));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsTrueWithSameValues() {
    ImmutableMap<BuckConfigKey, String> values = ImmutableMap.of(BuckConfigKey.parse("a.aa"), "b");

    ConfigSettingSelectable configSetting1 =
        ConfigSettingSelectable.of(
            values,
            ImmutableSet.of(
                TestSelectables.constraintValue("//a:x", "//a:xc"),
                TestSelectables.constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        ConfigSettingSelectable.of(
            values, ImmutableSet.of(TestSelectables.constraintValue("//a:x", "//a:xc")));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsTrueWithSpecializedValues() {
    ConfigSettingSelectable configSetting1 =
        ConfigSettingSelectable.of(
            ImmutableMap.of(BuckConfigKey.parse("a.aa"), "b", BuckConfigKey.parse("c.cc"), "d"),
            ImmutableSet.of(
                TestSelectables.constraintValue("//a:x", "//a:xc"),
                TestSelectables.constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        ConfigSettingSelectable.of(
            ImmutableMap.of(BuckConfigKey.parse("a.aa"), "b"),
            ImmutableSet.of(TestSelectables.constraintValue("//a:x", "//a:xc")));

    assertTrue(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsFalseWithNonSpecializedValues() {
    ConfigSettingSelectable configSetting1 =
        ConfigSettingSelectable.of(
            ImmutableMap.of(BuckConfigKey.parse("a.aa"), "b"),
            ImmutableSet.of(
                TestSelectables.constraintValue("//a:x", "//a:xc"),
                TestSelectables.constraintValue("//a:y", "//a:yc")));

    ConfigSettingSelectable configSetting2 =
        ConfigSettingSelectable.of(
            ImmutableMap.of(BuckConfigKey.parse("a.aa"), "b", BuckConfigKey.parse("c.cc"), "d"),
            ImmutableSet.of(TestSelectables.constraintValue("//a:x", "//a:xc")));

    assertFalse(configSetting1.refines(configSetting2));
  }

  @Test
  public void refinesReturnsFalseWithNonSpecializedConstraints() {
    ImmutableMap<BuckConfigKey, String> values = ImmutableMap.of(BuckConfigKey.parse("a.aa"), "b");

    ConfigSettingSelectable configSetting1 =
        ConfigSettingSelectable.of(
            values, ImmutableSet.of(TestSelectables.constraintValue("//a:x", "//a:xc")));

    ConfigSettingSelectable configSetting2 =
        ConfigSettingSelectable.of(
            values,
            ImmutableSet.of(
                TestSelectables.constraintValue("//a:x", "//a:xc"),
                TestSelectables.constraintValue("//a:y", "//a:yc")));

    assertFalse(configSetting1.refines(configSetting2));
  }
}
