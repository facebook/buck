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

package com.facebook.buck.core.description.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.rules.configsetting.ConfigSettingDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import org.junit.Test;

public class DescriptionCacheTest {

  @Test
  public void testBuildRuleHasRuleType() {
    assertEquals(
        "java_library", DescriptionCache.getRuleType(JavaLibraryDescription.class).getName());
  }

  @Test
  public void testConfigRuleHasRuleType() {
    assertEquals(
        "config_setting", DescriptionCache.getRuleType(ConfigSettingDescription.class).getName());
  }
}
