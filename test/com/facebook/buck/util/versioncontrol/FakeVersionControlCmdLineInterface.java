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

import com.google.common.collect.ImmutableSet;

public class FakeVersionControlCmdLineInterface extends NoOpCmdLineInterface {

  private final FullVersionControlStats versionControlStats;

  public FakeVersionControlCmdLineInterface(FullVersionControlStats versionControlStats) {
    this.versionControlStats = versionControlStats;
  }

  @Override
  public boolean isSupportedVersionControlSystem() throws InterruptedException {
    return true;
  }

  @Override
  public String currentRevisionId()
      throws VersionControlCommandFailedException, InterruptedException {
    return versionControlStats.getCurrentRevisionId();
  }

  @Override
  public String diffBetweenRevisions(String baseRevision, String tipRevision)
      throws VersionControlCommandFailedException, InterruptedException {
    if (!versionControlStats.getDiff().isPresent()) {
      throw new VersionControlCommandFailedException("");
    }
    return versionControlStats.getDiff().get();
  }

  @Override
  public ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    return versionControlStats.getPathsChangedInWorkingDirectory();
  }

  @Override
  public FastVersionControlStats fastVersionControlStats()
      throws InterruptedException, VersionControlCommandFailedException {
    return FastVersionControlStats.of(
        versionControlStats.getCurrentRevisionId(),
        versionControlStats.getBaseBookmarks(),
        versionControlStats.getBranchedFromMasterRevisionId(),
        versionControlStats.getBranchedFromMasterTS());
  }
}
