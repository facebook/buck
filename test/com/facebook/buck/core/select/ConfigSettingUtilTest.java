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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.stream.Stream;
import org.junit.Test;

public class ConfigSettingUtilTest {

  private static ConstraintValue constraintValue(String buildTarget, String constraintSetting) {
    return ConstraintValue.of(
        ConfigurationBuildTargetFactoryForTests.newInstance(buildTarget),
        ConstraintSetting.of(
            ConfigurationBuildTargetFactoryForTests.newInstance(constraintSetting)));
  }

  private static ConfigSettingSelectable configSetting(ConstraintValue... constraintValues) {
    return ConfigSettingSelectable.of(ImmutableMap.of(), ImmutableSet.copyOf(constraintValues));
  }

  private static boolean unambiguous(ConfigSettingSelectable... configSettings) {
    try {
      ConfigSettingUtil.checkUnambiguous(
          Stream.of(configSettings)
              .map(x -> new Pair<>(x, (Object) x.toString()))
              .collect(ImmutableList.toImmutableList()),
          DependencyStack.root());
      return true;
    } catch (HumanReadableException e) {
      return false;
    }
  }

  @Test
  public void unambiguous() {
    ConstraintValue linux = constraintValue("//:linux", "//:os");
    ConstraintValue windows = constraintValue("//:windows", "//:os");
    ConstraintValue arm64 = constraintValue("//:arm64", "//:cpu");
    ConstraintValue clang = constraintValue("//:clang", "//:compiler");

    // Cannot be both windows and linux
    assertTrue(unambiguous(configSetting(windows), configSetting(linux)));
    // linux-arm64 is more specific than linux
    assertTrue(unambiguous(configSetting(linux), configSetting(linux, arm64)));
    // Cannot be both windows and linux
    assertTrue(unambiguous(configSetting(linux, clang), configSetting(windows, arm64)));
    // linux-arm64 configuration can match both and neither is more specific
    assertFalse(unambiguous(configSetting(linux), configSetting(arm64)));
    // Identical keys in constraint
    assertFalse(unambiguous(configSetting(linux), configSetting(linux)));
    // linux-clang-arm64 can match both
    assertFalse(unambiguous(configSetting(linux, arm64), configSetting(linux, clang)));
  }

  @Test
  public void unambiguousThree() {
    ConstraintValue linux = constraintValue("//:linux", "//:os");
    ConstraintValue clang = constraintValue("//:clang", "//:compiler");

    // linux and clang are ambiguous
    assertFalse(unambiguous(configSetting(linux), configSetting(clang)));

    // linux, clang and linux-clang are unambiguous
    // because linux-clang is more specific than each other
    assertTrue(
        unambiguous(configSetting(linux), configSetting(clang), configSetting(linux, clang)));
  }

  @Test
  public void unambiguousFour() {
    ConstraintValue linux = constraintValue("//:linux", "//:os");
    ConstraintValue windows = constraintValue("//:windows", "//:os");
    ConstraintValue clang = constraintValue("//:clang", "//:compiler");
    ConstraintValue gcc = constraintValue("//:gcc", "//:compiler");

    // This is ambiguous because windows-gcc matches both windows and gcc
    assertFalse(
        unambiguous(
            configSetting(gcc),
            configSetting(linux, gcc),
            configSetting(windows),
            configSetting(windows, clang)));
  }

  @Test
  public void isSubsetConfigSetting() {
    ConstraintValue linux = constraintValue("//:linux", "//:os");
    ConstraintValue windows = constraintValue("//:windows", "//:os");
    ConstraintValue clang = constraintValue("//:clang", "//:compiler");
    ConstraintValue gcc = constraintValue("//:gcc", "//:compiler");
    assertTrue(ConfigSettingUtil.isSubset(configSetting(), configSetting()));
    assertTrue(ConfigSettingUtil.isSubset(configSetting(linux), configSetting()));
    assertTrue(ConfigSettingUtil.isSubset(configSetting(linux, clang), configSetting()));
    assertTrue(ConfigSettingUtil.isSubset(configSetting(linux, clang), configSetting(linux)));
    assertTrue(
        ConfigSettingUtil.isSubset(configSetting(linux, clang), configSetting(linux, clang)));
    assertFalse(ConfigSettingUtil.isSubset(configSetting(linux, clang), configSetting(linux, gcc)));
    assertFalse(ConfigSettingUtil.isSubset(configSetting(linux), configSetting(linux, clang)));
    assertFalse(ConfigSettingUtil.isSubset(configSetting(linux), configSetting(windows)));
  }

  private static AnySelectable or(ConfigSettingSelectable... css) {
    return AnySelectable.of(ImmutableList.copyOf(css));
  }

  @Test
  public void isSubsetConfigSettingAny() {
    ConstraintValue linux = constraintValue("//:linux", "//:os");
    ConstraintValue windows = constraintValue("//:windows", "//:os");
    ConstraintValue clang = constraintValue("//:clang", "//:compiler");
    ConstraintValue gcc = constraintValue("//:gcc", "//:compiler");
    assertFalse(ConfigSettingUtil.isSubset(configSetting(), or()));
    assertTrue(ConfigSettingUtil.isSubset(configSetting(), AnySelectable.any()));
    assertTrue(ConfigSettingUtil.isSubset(configSetting(linux), AnySelectable.any()));
    assertTrue(
        ConfigSettingUtil.isSubset(
            configSetting(linux), or(configSetting(windows), configSetting(linux))));
    assertTrue(
        ConfigSettingUtil.isSubset(
            configSetting(linux), or(configSetting(windows), configSetting(linux))));
    assertTrue(
        ConfigSettingUtil.isSubset(
            configSetting(linux, clang), or(configSetting(windows), configSetting(linux))));
    assertFalse(
        ConfigSettingUtil.isSubset(
            configSetting(linux), or(configSetting(linux, gcc), configSetting(linux, clang))));
  }

  @Test
  public void isSubsetAny() {
    ConstraintValue linux = constraintValue("//:linux", "//:os");
    ConstraintValue windows = constraintValue("//:windows", "//:os");
    ConstraintValue clang = constraintValue("//:clang", "//:compiler");
    ConstraintValue gcc = constraintValue("//:gcc", "//:compiler");
    assertTrue(
        ConfigSettingUtil.isSubset(
            or(configSetting(linux, clang), configSetting(windows, gcc)),
            or(configSetting(linux), configSetting(windows))));
    assertFalse(
        ConfigSettingUtil.isSubset(
            or(configSetting(linux), configSetting(windows)), or(configSetting(linux))));
  }
}
