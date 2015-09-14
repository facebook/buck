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

import com.facebook.buck.cli.FakeBuckConfig;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class VersionControlBuckConfigTest {
  private static final String HG_CMD = "myhg";
  private static final String GENERATE_STATISTICS = "true";
  private static final boolean GENERATE_STATISTICS_RESULT = true;

  @Test
  public void givenHgCmdInConfigThenReturnHgCmdFromConfig() {
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            VersionControlBuckConfig.VC_SECTION_KEY,
            ImmutableMap.of(VersionControlBuckConfig.HG_CMD_SETTING_KEY, HG_CMD)));
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(HG_CMD, is(equalTo(config.getHgCmd())));
  }

  @Test
  public void givenHgCmdNotInConfigThenReturnDefault() {
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            VersionControlBuckConfig.VC_SECTION_KEY,
            ImmutableMap.<String, String>of()));
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(VersionControlBuckConfig.HG_CMD_DEFAULT, is(equalTo(config.getHgCmd())));
  }

  @Test
  public void givenGenerateStatisticsInConfigThenReturnGenerateStatisticsFromConfig() {
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            VersionControlBuckConfig.VC_SECTION_KEY,
            ImmutableMap.of(
                VersionControlBuckConfig.GENERATE_STATISTICS_KEY, GENERATE_STATISTICS)));
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(GENERATE_STATISTICS_RESULT, is(equalTo(config.shouldGenerateStatistics())));
  }

  @Test
  public void givenGenerateStatisticsNotInConfigThenReturnDefault() {
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            VersionControlBuckConfig.VC_SECTION_KEY,
            ImmutableMap.<String, String>of()));
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(
        VersionControlBuckConfig.GENERATE_STATISTICS_DEFAULT,
        is(equalTo(config.shouldGenerateStatistics())));
  }
}
