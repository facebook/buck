/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.util.config;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.Reader;
import java.io.StringReader;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class RawConfigTest {
  @Test
  public void entriesAreStoredInFileOrder() throws Exception {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "one =   //foo:one",
                    "two =   //foo:two",
                    "three = //foo:three",
                    "four  = //foo:four"));
    RawConfig rawConfig = RawConfig.builder().putAll(Inis.read(reader)).build();
    assertThat(
        "entries are sorted in the order that they appear in the file",
        rawConfig.getSection("alias").keySet(),
        contains("one", "two", "three", "four"));
  }

  @Test
  public void duplicateValuesAreOverridden() {
    RawConfig rawConfig =
        RawConfig.builder()
            .putAll(ImmutableMap.of("section", ImmutableMap.of("field", "value1")))
            .putAll(ImmutableMap.of("section", ImmutableMap.of("field", "value2")))
            .build();
    assertThat(rawConfig.getValue("section", "field"), equalTo(Optional.of("value2")));
  }

  @Test
  public void emptySectionReturnsEmptyList() {
    assertThat(RawConfig.builder().build().getSection("foo"), Matchers.anEmptyMap());
  }

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

    ImmutableSet<String> diff = rawConfig1.compare(rawConfig2);
    assertTrue(diff.isEmpty());
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

    ImmutableSet<String> diff = rawConfig1.compare(rawConfig2);
    assertEquals(4, diff.size());
    assertThat(
        diff,
        containsInAnyOrder(
            "section1.field1",
            "section3.onlyOnLeft",
            "section4.onlyOnLeft",
            "section5.onlyOnRight"));
  }
}
