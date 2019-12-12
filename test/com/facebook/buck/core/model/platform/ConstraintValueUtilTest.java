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

package com.facebook.buck.core.model.platform;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.google.common.collect.ImmutableSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ConstraintValueUtilTest {
  private static ConstraintValue constraintValue(String value, String setting) {
    return ConstraintValue.of(
        ConfigurationBuildTargetFactoryForTests.newInstance(value),
        ConstraintSetting.of(ConfigurationBuildTargetFactoryForTests.newInstance(setting)));
  }

  @Test
  public void validateUniqueConstraintSettingsGood() {
    ConstraintValueUtil.validateUniqueConstraintSettings(
        "my_rule",
        ConfigurationBuildTargetFactoryForTests.newInstance("//:z"),
        DependencyStack.root(),
        ImmutableSet.of(
            constraintValue("//:jupiter", "//:planet"),
            constraintValue("//:karaganda", "//:city"),
            constraintValue("//:aristotle", "//:philosopher")));
  }

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void validateUniqueConstraintSettingsNonUnique() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "in my_rule rule //:z: Duplicate constraint values detected: "
            + "constraint_setting //:wonder has [//:lighthouse, //:pyramid]");

    ConstraintValueUtil.validateUniqueConstraintSettings(
        "my_rule",
        ConfigurationBuildTargetFactoryForTests.newInstance("//:z"),
        DependencyStack.root(),
        ImmutableSet.of(
            constraintValue("//:pyramid", "//:wonder"),
            constraintValue("//:lighthouse", "//:wonder"),
            constraintValue("//:vim", "//:editor")));
  }
}
