/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.support.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.command.config.ConfigDifference;
import com.facebook.buck.command.config.ImmutableConfigChange;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class BuckGlobalStateCompatibilityCellCheckerTest {
  @Test
  public void equalsForBuckGlobalStateInvalidation() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "3"),
                    "cxx", ImmutableMap.of("cc", "/some_location/gcc")))
            .build();
    BuckConfig buckConfigMoreThreads =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "4"),
                    "cxx", ImmutableMap.of("cc", "/some_location/gcc")))
            .build();
    BuckConfig buckConfigDifferentCompiler =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "3"),
                    "cxx", ImmutableMap.of("cc", "/some_location/clang")))
            .build();

    assertEquals(
        ImmutableMap.of(
            "cxx.cc", new ImmutableConfigChange("/some_location/gcc", "/some_location/clang")),
        ConfigDifference.compareForCaching(buckConfig, buckConfigDifferentCompiler));

    assertEquals(
        ImmutableMap.of(), ConfigDifference.compareForCaching(buckConfig, buckConfigMoreThreads));

    assertNotEquals(buckConfig, buckConfigMoreThreads);
    assertNotEquals(buckConfig, buckConfigDifferentCompiler);

    assertTrue(ConfigDifference.compareForCaching(buckConfig, buckConfigMoreThreads).isEmpty());
    assertFalse(
        ConfigDifference.compareForCaching(buckConfig, buckConfigDifferentCompiler).isEmpty());
    assertFalse(
        ConfigDifference.compareForCaching(buckConfigMoreThreads, buckConfigDifferentCompiler)
            .isEmpty());
  }

  @Test
  public void emptySectionsIgnoredWhenComparingBuckConfig() {
    BuckConfig buckConfigWithEmptyValue =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of("cc", "/some_location/gcc"),
                    "client", ImmutableMap.of()))
            .build();
    BuckConfig buckConfigWithRealValue =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of("cc", "/some_location/gcc"),
                    "client", ImmutableMap.of("id", "clientid")))
            .build();

    assertNotEquals(buckConfigWithEmptyValue, buckConfigWithRealValue);

    assertTrue(
        ConfigDifference.compareForCaching(buckConfigWithEmptyValue, buckConfigWithRealValue)
            .isEmpty());
  }

  @Test
  public void emptySectionAddedOrRemoved() {
    BuckConfig buckConfigWithRealValue =
        FakeBuckConfig.builder().setSections(ImmutableMap.of()).build();
    BuckConfig buckConfigWithEmptyValue =
        FakeBuckConfig.builder().setSections(ImmutableMap.of("test", ImmutableMap.of())).build();

    assertTrue(
        ConfigDifference.compareForCaching(buckConfigWithEmptyValue, buckConfigWithRealValue)
            .isEmpty());
    assertTrue(
        ConfigDifference.compareForCaching(buckConfigWithRealValue, buckConfigWithEmptyValue)
            .isEmpty());
  }

  @Test
  public void newValueAddedOrRemoved() {
    BuckConfig buckConfigWithoutValue =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("cxx", ImmutableMap.of("cc", "/some_location/gcc")))
            .build();
    BuckConfig buckConfigWithValue =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("cxx", ImmutableMap.of("cc", "/some_location/gcc", "new", "value")))
            .build();

    assertEquals(
        ImmutableMap.of("cxx.new", new ImmutableConfigChange(null, "value")),
        ConfigDifference.compareForCaching(buckConfigWithoutValue, buckConfigWithValue));
    assertEquals(
        ImmutableMap.of("cxx.new", new ImmutableConfigChange("value", null)),
        ConfigDifference.compareForCaching(buckConfigWithValue, buckConfigWithoutValue));
  }

  @Test
  public void newSectionAddedOrRemoved() {
    BuckConfig buckConfigWithoutValue =
        FakeBuckConfig.builder().setSections(ImmutableMap.of()).build();
    BuckConfig buckConfigWithValue =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("cxx", ImmutableMap.of("new", "value")))
            .build();

    assertEquals(
        ImmutableMap.of("cxx.new", new ImmutableConfigChange(null, "value")),
        ConfigDifference.compareForCaching(buckConfigWithoutValue, buckConfigWithValue));
    assertEquals(
        ImmutableMap.of("cxx.new", new ImmutableConfigChange("value", null)),
        ConfigDifference.compareForCaching(buckConfigWithValue, buckConfigWithoutValue));
  }
}
