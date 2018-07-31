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
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
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
}
