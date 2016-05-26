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

package com.facebook.buck.rage;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.versioncontrol.VersionControlCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

/**
 * Responsible foe getting information out of the version control system.
 */
public class VcsInfoCollector {

  private static final Logger LOG = Logger.get(InteractiveReport.class);

  private final VersionControlCmdLineInterface vcCmdLineInterface;

  private VcsInfoCollector(VersionControlCmdLineInterface vcCmdLineInterface) {
    this.vcCmdLineInterface = vcCmdLineInterface;
  }

  public static Optional<VcsInfoCollector> create(
      VersionControlCmdLineInterface vcCmdLineInterface) {
    if (!vcCmdLineInterface.isSupportedVersionControlSystem()) {
      return Optional.absent();
    }
    return Optional.of(new VcsInfoCollector(vcCmdLineInterface));
  }

  public SourceControlInfo gatherScmInformation()
      throws InterruptedException, VersionControlCommandFailedException {
    Optional<ImmutableSet<String>> filesChangedFromMasterBranchPoint = Optional.absent();
    try {
      filesChangedFromMasterBranchPoint =
          Optional.of(vcCmdLineInterface.changedFiles("ancestor(., remote/master)"));
    } catch (VersionControlCommandFailedException e) {
      // It's fine if the user doesn't have a remote/master bookmark.
      LOG.info(e, "Couldn't get files changed");
    }

    return SourceControlInfo.builder()
        .setRevisionId(vcCmdLineInterface.currentRevisionId())
        .setDirtyFiles(vcCmdLineInterface.changedFiles("."))
        .setFilesChangedFromMasterBranchPoint(filesChangedFromMasterBranchPoint)
        .build();
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractSourceControlInfo {
    String getRevisionId();
    ImmutableSet<String> getDirtyFiles();
    Optional<ImmutableSet<String>> getFilesChangedFromMasterBranchPoint();
  }
}
