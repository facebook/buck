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
package com.facebook.buck.test.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.BuckConfigTestUtils;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TestBuckConfigTest {

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testExcludedLabels() throws IOException {
    Reader reader =
        new StringReader(Joiner.on('\n').join("[test]", "excluded_labels = windows, linux"));
    TestBuckConfig config =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader)
            .getView(TestBuckConfig.class);

    assertEquals(
        ImmutableList.of("windows", "linux"), config.getDefaultRawExcludedLabelSelectors());
  }

  @Test
  public void testGetDefaultTestTimeoutMillis() throws IOException {
    assertEquals(
        0L,
        FakeBuckConfig.builder()
            .build()
            .getView(TestBuckConfig.class)
            .getDefaultTestTimeoutMillis());

    Reader reader = new StringReader(Joiner.on('\n').join("[test]", "timeout = 54321"));
    TestBuckConfig config =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader)
            .getView(TestBuckConfig.class);
    assertEquals(54321L, config.getDefaultTestTimeoutMillis());
  }

  @Test
  public void testTestThreadUtilizationRatioDefaultValue() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("threads", "10")))
            .build();
    assertThat(buckConfig.getView(TestBuckConfig.class).getNumTestThreads(), Matchers.equalTo(10));
  }

  @Test
  public void testTestThreadUtilizationRatioRoundsUp() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "10"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "0.51")))
            .build();
    assertThat(buckConfig.getView(TestBuckConfig.class).getNumTestThreads(), Matchers.equalTo(6));
  }

  @Test
  public void testTestThreadUtilizationRatioGreaterThanZero() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "1"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "0.00001")))
            .build();
    assertThat(buckConfig.getView(TestBuckConfig.class).getNumTestThreads(), Matchers.equalTo(1));
  }

  @Test
  public void testTestThreadUtilizationRatioZero() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.startsWith("thread_utilization_ratio must be greater than zero"));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "1"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "0")))
            .build();
    buckConfig.getView(TestBuckConfig.class).getNumTestThreads();
  }

  @Test
  public void testTestThreadUtilizationRatioLessThanZero() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.startsWith("thread_utilization_ratio must be greater than zero"));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "build", ImmutableMap.of("threads", "1"),
                    "test", ImmutableMap.of("thread_utilization_ratio", "-0.00001")))
            .build();
    buckConfig.getView(TestBuckConfig.class).getNumTestThreads();
  }
}
