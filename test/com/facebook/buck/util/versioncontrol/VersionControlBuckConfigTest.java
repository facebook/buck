/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.versioncontrol;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.junit.Test;

public class VersionControlBuckConfigTest {
  private static final String HG_CMD = "myhg";
  private static final String GENERATE_STATISTICS = "true";
  private static final boolean GENERATE_STATISTICS_RESULT = true;

  @Test
  public void givenHgCmdInConfigThenReturnHgCmdFromConfig() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    VersionControlBuckConfig.VC_SECTION_KEY,
                    ImmutableMap.of(VersionControlBuckConfig.HG_CMD_SETTING_KEY, HG_CMD)))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(HG_CMD, is(equalTo(config.getHgCmd())));
  }

  @Test
  public void givenHgCmdNotInConfigThenReturnDefault() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(VersionControlBuckConfig.VC_SECTION_KEY, ImmutableMap.of()))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(VersionControlBuckConfig.HG_CMD_DEFAULT, is(equalTo(config.getHgCmd())));
  }

  @Test
  public void givenGenerateStatisticsInConfigThenReturnGenerateStatisticsFromConfig() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    VersionControlBuckConfig.VC_SECTION_KEY,
                    ImmutableMap.of(
                        VersionControlBuckConfig.GENERATE_STATISTICS_KEY, GENERATE_STATISTICS)))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(GENERATE_STATISTICS_RESULT, is(equalTo(config.shouldGenerateStatistics())));
  }

  @Test
  public void givenGenerateStatisticsNotInConfigThenReturnDefault() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(VersionControlBuckConfig.VC_SECTION_KEY, ImmutableMap.of()))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(
        VersionControlBuckConfig.GENERATE_STATISTICS_DEFAULT,
        is(equalTo(config.shouldGenerateStatistics())));
  }

  @Test
  public void givenNoPregeneratedStatsReturnsNothing() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(VersionControlBuckConfig.VC_SECTION_KEY, ImmutableMap.of()))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(config.getPregeneratedVersionControlStats(), is(equalTo(Optional.empty())));
  }

  @Test
  public void givenCompletePregeneratedStatsReturnsSomething() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    VersionControlBuckConfig.VC_SECTION_KEY,
                    ImmutableMap.of(
                        VersionControlBuckConfig.PREGENERATED_CURRENT_REVISION_ID, "f00",
                        VersionControlBuckConfig.PREGENERATED_BASE_BOOKMARKS, "remote/master",
                        VersionControlBuckConfig.PREGENERATED_BASE_REVISION_ID, "b47",
                        VersionControlBuckConfig.PREGENERATED_BASE_REVISION_TIMESTAMP, "0")))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(config.getPregeneratedVersionControlStats().isPresent(), is(equalTo(true)));
  }

  @Test(expected = HumanReadableException.class)
  public void givenIncompletePregeneratedStatsThrowsException() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    VersionControlBuckConfig.VC_SECTION_KEY,
                    ImmutableMap.of(
                        VersionControlBuckConfig.PREGENERATED_CURRENT_REVISION_ID, "f00")))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    config.getPregeneratedVersionControlStats();
  }
}
