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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

/**
 * Responsible foe getting information out of the version control system.
 */
public class VcsInfoCollector {

  private static final Logger LOG = Logger.get(InteractiveReport.class);
  private static final String REMOTE_MASTER = "remote/master";

  private static final ImmutableSet<String> TRACKED_BOOKMARKS = ImmutableSet.of(
      REMOTE_MASTER);

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
    Optional<String> masterRevisionId = getMasterRevisionId();
    Optional<String> diffBase = masterRevisionId;
    String currentRevisionId = vcCmdLineInterface.currentRevisionId();

    Optional<ImmutableSet<String>> filesChangedFromMasterBranchPoint = Optional.absent();
    Optional<ImmutableSet<String>> diffBaseBookmarks = Optional.absent();
    Optional<String> producedDiff = Optional.absent();

    if (masterRevisionId.isPresent()) {
      diffBase = getCommonAncestorRevisionId(currentRevisionId, masterRevisionId.get());
      if (diffBase.isPresent()) {
        filesChangedFromMasterBranchPoint = Optional.of(
            vcCmdLineInterface.changedFiles(diffBase.get()));
        diffBaseBookmarks = Optional.of(getBasedOffWhichTrackedBookmark(diffBase.get()));
        producedDiff = Optional.of(
            vcCmdLineInterface.diffBetweenRevisions(currentRevisionId, diffBase.get()));
      }
    }

    return SourceControlInfo.builder()
        .setCurrentRevisionId(currentRevisionId)
        .setBasedOffWhichTracked(diffBaseBookmarks)
        .setRevisionIdOffTracked(diffBase)
        .setDiff(producedDiff)
        .setDirtyFiles(vcCmdLineInterface.changedFiles("."))
        .setFilesChangedFromMasterBranchPoint(filesChangedFromMasterBranchPoint)
        .build();
  }

  private ImmutableSet<String> getBasedOffWhichTrackedBookmark(
      String diffBaseRevisionId) throws InterruptedException {
    ImmutableSet.Builder<String> bookmarkSet = ImmutableSet.builder();
    for (String bookmark : TRACKED_BOOKMARKS) {
      try {
        if (diffBaseRevisionId.equals(vcCmdLineInterface.revisionId(bookmark))) {
          bookmarkSet.add(bookmark);
        }
      } catch (VersionControlCommandFailedException e) {
        LOG.info("%s bookmark doesn't point to %s.", bookmark, diffBaseRevisionId);
      }
    }
    return bookmarkSet.build();
  }

  private Optional<String> getMasterRevisionId() throws InterruptedException {
    try {
      return Optional.of(vcCmdLineInterface.revisionId(REMOTE_MASTER));
    } catch (VersionControlCommandFailedException e) {
      LOG.info("Couldn't locate %s bookmark. Some information won't be available.", REMOTE_MASTER);
    }
    return Optional.absent();
  }

  private Optional<String> getCommonAncestorRevisionId(String revisionOne, String revisionTwo)
      throws InterruptedException {
    try {
      return Optional.of(vcCmdLineInterface.commonAncestor(revisionOne, revisionTwo));
    } catch (VersionControlCommandFailedException e) {
      LOG.info("Couldn't find common ancestor for %s & %s. Some information won't be available",
          revisionOne,
          revisionTwo);
    }
    return Optional.absent();
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractSourceControlInfo {
    String getCurrentRevisionId();
    Optional<ImmutableSet<String>> getBasedOffWhichTracked();
    Optional<String> getRevisionIdOffTracked();
    @JsonIgnore
    Optional<String> getDiff();
    ImmutableSet<String> getDirtyFiles();
    Optional<ImmutableSet<String>> getFilesChangedFromMasterBranchPoint();
  }
}
