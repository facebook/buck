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

package com.facebook.buck.command.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.config.RawConfig;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class ConfigDifferenceTest {
  @Test
  public void compareEqualConfigs() {
    RawConfig rawConfig1 =
        RawConfig.builder()
            .putAll(ImmutableMap.of("section", ImmutableMap.of("field", "value1")))
            .putAll(ImmutableMap.of("section", ImmutableMap.of("field", "value2")))
            .build();

    RawConfig rawConfig2 =
        RawConfig.builder()
            .putAll(ImmutableMap.of("section", ImmutableMap.of("field", "value1")))
            .putAll(ImmutableMap.of("section", ImmutableMap.of("field", "value2")))
            .build();

    assertTrue(ConfigDifference.compare(rawConfig1.getValues(), rawConfig2.getValues()).isEmpty());
  }

  @Test
  public void compareNotEqualConfigs() {
    RawConfig rawConfig1 =
        RawConfig.builder()
            .putAll(ImmutableMap.of("section1", ImmutableMap.of("field1", "diffValue1")))
            .putAll(ImmutableMap.of("section2", ImmutableMap.of("field2", "someValue")))
            .putAll(ImmutableMap.of("section3", ImmutableMap.of("onlyOnLeft", "someValue")))
            .putAll(ImmutableMap.of("section4", ImmutableMap.of("onlyOnLeft", "someValue")))
            .build();

    RawConfig rawConfig2 =
        RawConfig.builder()
            .putAll(ImmutableMap.of("section1", ImmutableMap.of("field1", "diffValue2")))
            .putAll(ImmutableMap.of("section2", ImmutableMap.of("field2", "someValue")))
            .putAll(ImmutableMap.of("section5", ImmutableMap.of("onlyOnRight", "someValue")))
            .build();

    assertEquals(
        ConfigDifference.compare(rawConfig1.getValues(), rawConfig2.getValues()),
        ImmutableMap.of(
            "section1.field1", ImmutableConfigChange.of("diffValue1", "diffValue2"),
            "section3.onlyOnLeft", ImmutableConfigChange.of("someValue", null),
            "section4.onlyOnLeft", ImmutableConfigChange.of("someValue", null),
            "section5.onlyOnRight", ImmutableConfigChange.of(null, "someValue")));
  }
}
