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
package com.facebook.buck.command.config;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class BuildBuckConfigTest {

  @Test
  public void testShouldSetNumberOfThreadsFromBuckConfig() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("threads", "3")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getNumThreads(), Matchers.equalTo(3));
  }

  @Test
  public void testDefaultsNumberOfBuildThreadsToOneAndAQuarterTheNumberOfAvailableProcessors() {
    BuildBuckConfig buckConfig = FakeBuckConfig.builder().build().getView(BuildBuckConfig.class);
    assertThat(
        buckConfig.getNumThreads(), Matchers.equalTo(Runtime.getRuntime().availableProcessors()));
  }

  @Test
  public void testDefaultsNumberOfBuildThreadsSpecified() {
    BuildBuckConfig buckConfig = FakeBuckConfig.builder().build().getView(BuildBuckConfig.class);
    assertThat(buckConfig.getNumThreads(42), Matchers.equalTo(42));
  }

  @Test
  public void testBuildThreadsRatioSanityCheck() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "1")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(10));
  }

  @Test
  public void testBuildThreadsRatioGreaterThanZero() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0.00001")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(1), Matchers.equalTo(1));
  }

  @Test
  public void testBuildThreadsRatioRoundsUp() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0.3")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(4), Matchers.equalTo(2));
  }

  @Test
  public void testNonZeroBuildThreadsRatio() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0.1")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(1), Matchers.equalTo(1));
  }

  @Test
  public void testZeroBuildThreadsRatio() {
    try {
      BuildBuckConfig buckConfig =
          FakeBuckConfig.builder()
              .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "0")))
              .build()
              .getView(BuildBuckConfig.class);
      buckConfig.getDefaultMaximumNumberOfThreads(1);
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.startsWith("thread_core_ratio must be greater than zero"));
    }
  }

  @Test
  public void testLessThanZeroBuildThreadsRatio() {
    try {
      BuildBuckConfig buckConfig =
          FakeBuckConfig.builder()
              .setSections(ImmutableMap.of("build", ImmutableMap.of("thread_core_ratio", "-0.1")))
              .build()
              .getView(BuildBuckConfig.class);
      buckConfig.getDefaultMaximumNumberOfThreads(1);
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.startsWith("thread_core_ratio must be greater than zero"));
    }
  }

  @Test
  public void testBuildThreadsRatioWithReservedCores() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build",
                    ImmutableMap.of(
                        "thread_core_ratio", "1",
                        "thread_core_ratio_reserved_cores", "2")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(8));
  }

  @Test
  public void testCappedBuildThreadsRatio() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build",
                    ImmutableMap.of(
                        "thread_core_ratio", "0.5",
                        "thread_core_ratio_max_threads", "4")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(4));
  }

  @Test
  public void testFloorLimitedBuildThreadsRatio() {
    BuildBuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build",
                    ImmutableMap.of(
                        "thread_core_ratio", "0.25",
                        "thread_core_ratio_min_threads", "6")))
            .build()
            .getView(BuildBuckConfig.class);
    assertThat(buckConfig.getDefaultMaximumNumberOfThreads(10), Matchers.equalTo(6));
  }
}
