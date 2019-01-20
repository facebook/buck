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

import com.google.common.collect.ImmutableSet;
import java.io.InputStream;
import java.util.Optional;

/** * Provides meta-data about the version control repository the project being built is using. */
public interface VersionControlCmdLineInterface {
  /** @return true if project is using version control, and we support it (i.e. hg) */
  boolean isSupportedVersionControlSystem() throws InterruptedException;

  /**
   * @param baseRevision
   * @param tipRevision
   * @return {@link VersionControlSupplier} of the input stream of the diff between two revisions
   * @throws InterruptedException
   */
  VersionControlSupplier<InputStream> diffBetweenRevisions(String baseRevision, String tipRevision)
      throws VersionControlCommandFailedException, InterruptedException;

  /**
   * @param baseRevision
   * @param tipRevision
   * @return {@link VersionControlSupplier} of the input stream of the diff between two revisions or
   *     {@link Optional#empty}
   * @throws InterruptedException
   */
  default Optional<VersionControlSupplier<InputStream>> diffBetweenRevisionsOrAbsent(
      String baseRevision, String tipRevision) throws InterruptedException {
    try {
      return Optional.of(diffBetweenRevisions(baseRevision, tipRevision));
    } catch (VersionControlCommandFailedException e) {
      return Optional.empty();
    }
  }

  /**
   * @param fromRevisionId
   * @return files changed from the given revision.
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException;

  FastVersionControlStats fastVersionControlStats()
      throws InterruptedException, VersionControlCommandFailedException;
}
