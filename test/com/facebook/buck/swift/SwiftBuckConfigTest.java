/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.swift;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.junit.Test;

public class SwiftBuckConfigTest {

  @Test
  public void testGetFlags() {
    SwiftBuckConfig swiftBuckConfig =
        new SwiftBuckConfig(
            FakeBuckConfig.builder()
                .setSections(ImmutableMap.of("swift", ImmutableMap.of("compiler_flags", "-g")))
                .build());
    assertThat(swiftBuckConfig.getCompilerFlags(), not(equalTo(Optional.empty())));
    assertThat(swiftBuckConfig.getCompilerFlags().get(), contains("-g"));
  }

  @Test
  public void testAbsentFlags() {
    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(FakeBuckConfig.builder().build());
    assertThat(swiftBuckConfig.getCompilerFlags(), equalTo(Optional.empty()));
  }
}
