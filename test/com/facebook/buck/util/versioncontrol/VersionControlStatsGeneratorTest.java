/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;
import org.junit.Test;

public class VersionControlStatsGeneratorTest {

  private final FullVersionControlStats expected =
      FullVersionControlStats.builder()
          .setCurrentRevisionId("f00")
          .setBranchedFromMasterRevisionId("b47")
          .setBranchedFromMasterTS(0L)
          .setBaseBookmarks(ImmutableSet.of("remote/master"))
          .setDiff(
              () -> {
                try {
                  return new FileInputStream(
                      new File("/tmp/this_is_not_really_a_valid_diff_but_whatever.diff"));
                } catch (IOException e) {
                  throw new VersionControlCommandFailedException(e);
                }
              })
          .setPathsChangedInWorkingDirectory(ImmutableSet.of("hello.txt"))
          .build();

  private final VersionControlCmdLineInterface versionControlCmdLineInterface =
      new FakeVersionControlCmdLineInterface(expected);

  @Test
  public void fastModeGeneratesBasicStats() throws Exception {
    Optional<FullVersionControlStats> actual =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.empty())
            .generateStats(VersionControlStatsGenerator.Mode.FAST);
    assertThat(actual.isPresent(), is(equalTo(true)));
    assertThat(actual.get().getCurrentRevisionId(), is(equalTo(expected.getCurrentRevisionId())));
    assertThat(
        actual.get().getBranchedFromMasterRevisionId(),
        is(equalTo(expected.getBranchedFromMasterRevisionId())));
    assertThat(
        actual.get().getBranchedFromMasterTS(), is(equalTo(expected.getBranchedFromMasterTS())));
    assertThat(actual.get().getBaseBookmarks(), is(equalTo(expected.getBaseBookmarks())));
  }

  @Test
  public void fastModeDoesNotGenerateChangedFilesAndDiff() throws Exception {
    Optional<FullVersionControlStats> actual =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.empty())
            .generateStats(VersionControlStatsGenerator.Mode.FAST);
    assertThat(actual.isPresent(), is(equalTo(true)));
    assertThat(actual.get().getPathsChangedInWorkingDirectory(), is(empty()));
    assertThat(actual.get().getDiff().isPresent(), is(equalTo(false)));
  }

  @Test
  public void fullModeGeneratesChangedFilesAndDiff() throws Exception {
    Optional<FullVersionControlStats> actual =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.empty())
            .generateStats(VersionControlStatsGenerator.Mode.FULL);
    assertThat(actual.isPresent(), is(equalTo(true)));
    assertThat(
        actual.get().getPathsChangedInWorkingDirectory(),
        is(equalTo(expected.getPathsChangedInWorkingDirectory())));
    assertThat(actual.get().getDiff(), is(equalTo(expected.getDiff())));
  }

  @Test
  public void fastModeDoesNotReturnChangedFilesAndDiffIfTheyAreGenerated() throws Exception {
    VersionControlStatsGenerator versionControlStatsGenerator =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.empty());
    versionControlStatsGenerator.generateStats(VersionControlStatsGenerator.Mode.FULL);
    Optional<FullVersionControlStats> actual =
        versionControlStatsGenerator.generateStats(VersionControlStatsGenerator.Mode.FAST);
    assertThat(actual.isPresent(), is(equalTo(true)));
    assertThat(actual.get().getPathsChangedInWorkingDirectory(), is(empty()));
    assertThat(actual.get().getDiff().isPresent(), is(equalTo(false)));
  }

  @Test
  public void pregeneratedModeDoesNotGenerateStats() throws Exception {
    Optional<FullVersionControlStats> actual =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.empty())
            .generateStats(VersionControlStatsGenerator.Mode.PREGENERATED);
    assertThat(actual.isPresent(), is(equalTo(false)));
  }

  @Test
  public void pregeneratedDoesNotReturnStatsIfTheyAreGenerated() throws Exception {
    VersionControlStatsGenerator versionControlStatsGenerator =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.empty());
    versionControlStatsGenerator.generateStats(VersionControlStatsGenerator.Mode.FAST);
    Optional<FullVersionControlStats> actual =
        versionControlStatsGenerator.generateStats(VersionControlStatsGenerator.Mode.PREGENERATED);
    assertThat(actual.isPresent(), is(equalTo(false)));
  }

  @Test
  public void pregeneratedModeReturnsStats() throws Exception {
    FastVersionControlStats pregenerated =
        FastVersionControlStats.of(
            expected.getCurrentRevisionId(),
            expected.getBaseBookmarks(),
            expected.getBranchedFromMasterRevisionId(),
            expected.getBranchedFromMasterTS());

    Optional<FullVersionControlStats> actual =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.of(pregenerated))
            .generateStats(VersionControlStatsGenerator.Mode.PREGENERATED);
    assertThat(actual.isPresent(), is(equalTo(true)));
    assertThat(actual.get().getCurrentRevisionId(), is(equalTo(expected.getCurrentRevisionId())));
    assertThat(
        actual.get().getBranchedFromMasterRevisionId(),
        is(equalTo(expected.getBranchedFromMasterRevisionId())));
    assertThat(
        actual.get().getBranchedFromMasterTS(), is(equalTo(expected.getBranchedFromMasterTS())));
    assertThat(actual.get().getBaseBookmarks(), is(equalTo(expected.getBaseBookmarks())));
  }

  @Test
  public void pregeneratedStatsHavePrecedence() throws Exception {
    FastVersionControlStats pregenerated =
        FastVersionControlStats.of("cafe", ImmutableSet.of("remote/master"), "babe", 1L);
    Optional<FullVersionControlStats> actual =
        new VersionControlStatsGenerator(versionControlCmdLineInterface, Optional.of(pregenerated))
            .generateStats(VersionControlStatsGenerator.Mode.FULL);
    assertThat(actual.isPresent(), is(equalTo(true)));
    assertThat(
        actual.get().getCurrentRevisionId(), is(not(equalTo(expected.getCurrentRevisionId()))));
    assertThat(
        actual.get().getCurrentRevisionId(), is(equalTo(pregenerated.getCurrentRevisionId())));
    assertThat(
        actual.get().getBranchedFromMasterRevisionId(),
        is(not(equalTo(expected.getBranchedFromMasterRevisionId()))));
    assertThat(
        actual.get().getBranchedFromMasterRevisionId(),
        is(equalTo(pregenerated.getBranchedFromMasterRevisionId())));
    assertThat(
        actual.get().getBranchedFromMasterTS(),
        is(not(equalTo(expected.getBranchedFromMasterTS()))));
    assertThat(actual.get().getBaseBookmarks(), is(equalTo(pregenerated.getBaseBookmarks())));
  }
}
