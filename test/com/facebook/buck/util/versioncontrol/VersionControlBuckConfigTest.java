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

package com.facebook.buck.util.versioncontrol;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Test;

public class VersionControlBuckConfigTest {
  private static final String HG_CMD = "myhg";
  private static final String GENERATE_STATISTICS = "true";
  private static final boolean GENERATE_STATISTICS_RESULT = true;
  private static final String TRACKED_BOOKMARKS = "bookmark1, bookmark2";
  private static final ImmutableSet<String> TRACKED_BOOKMARKS_RESULT =
      ImmutableSet.of("bookmark1", "bookmark2");

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
    assertThat(config.getHgCmd(), is(equalTo(HG_CMD)));
  }

  @Test
  public void givenHgCmdNotInConfigThenReturnDefault() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(VersionControlBuckConfig.VC_SECTION_KEY, ImmutableMap.of()))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(config.getHgCmd(), is(equalTo(VersionControlBuckConfig.HG_CMD_DEFAULT)));
  }

  @Test
  public void givenTrackedBookmarksInConfigThenReturnTrackedBookmarksFromConfig() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    VersionControlBuckConfig.VC_SECTION_KEY,
                    ImmutableMap.of(
                        VersionControlBuckConfig.TRACKED_BOOKMARKS_KEY, TRACKED_BOOKMARKS)))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(config.getTrackedBookmarks(), is(equalTo(TRACKED_BOOKMARKS_RESULT)));
  }

  @Test
  public void givenTrackedBookmarksNotInConfigThenReturnDefault() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(VersionControlBuckConfig.VC_SECTION_KEY, ImmutableMap.of()))
            .build();
    VersionControlBuckConfig config = new VersionControlBuckConfig(buckConfig);
    assertThat(
        config.getTrackedBookmarks(),
        is(equalTo(VersionControlBuckConfig.TRACKED_BOOKMARKS_DEFAULT)));
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
    assertThat(config.shouldGenerateStatistics(), is(equalTo(GENERATE_STATISTICS_RESULT)));
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
        config.shouldGenerateStatistics(),
        is(equalTo(VersionControlBuckConfig.GENERATE_STATISTICS_DEFAULT)));
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
