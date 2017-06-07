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

package com.facebook.buck.util.autosparse;

import com.facebook.buck.util.versioncontrol.SparseSummary;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Track the state of a source control working copy, including what files could potentially be part
 * of a full checkout, and control the working copy sparse profile (what part of the full set of
 * files under source control are actually physically present on disk).
 */
public interface AutoSparseState {
  /** @return The root path of the working copy this state tracks. */
  Path getSCRoot() throws InterruptedException;

  /**
   * Query the source control manifest for information on a file.
   *
   * @param path the file to get the manifest information for
   * @return a {@link ManifestInfo} instance if this file is under source control, null otherwise.
   */
  @Nullable
  ManifestInfo getManifestInfoForFile(Path path);

  /**
   * Query if a file is available in the source control manifest (and is thus tracked by source
   * control)
   *
   * @param path to a file or directory
   * @return true if the path is a file that exists in the manifest, or a directory that contains
   *     files that are in the manifest.
   */
  boolean existsInManifest(Path path);

  /**
   * Add a path that should, at some point in the future, be part of the working copy sparse
   * profile.
   *
   * @param path The path to a source-control managed file or directory to be added
   */
  void addToSparseProfile(Path path);

  /**
   * Update the working copy to include new paths added to the sparse profile. Paths added with
   * <code>addToSparseProfile</code> will now be physically available on the filesystem.
   */
  SparseSummary materialiseSparseProfile() throws IOException, InterruptedException;
}
