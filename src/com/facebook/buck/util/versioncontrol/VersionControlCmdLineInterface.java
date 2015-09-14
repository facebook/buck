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

/***
 * Provides meta-data about the version control repository the project being built is using.
 */
public interface VersionControlCmdLineInterface {
  /***
   *
   * @return true if project is using version control, and we support it (i.e. hg)
   */
  boolean isSupportedVersionControlSystem();

  /***
   * @param name Bookmark name, e.g. master
   * @return Global revision ID for given name
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  String revisionId(String name) throws VersionControlCommandFailedException, InterruptedException;

  /***
   *
   * @return Revision ID for current tip
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  String currentRevisionId() throws VersionControlCommandFailedException, InterruptedException;

  /***
   *
   * @param revisionIdOne
   * @param revisionIdTwo
   * @return Revision ID that is an ancestor for both revisionIdOne and revisionIdTwo
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  String commonAncestor(String revisionIdOne, String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException;

  /***
   *
   * @return True if working directory has changes after last commit
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  boolean hasWorkingDirectoryChanges()
      throws VersionControlCommandFailedException, InterruptedException;

  /***
   *
   * @param revisionId
   * @return Unix timestamp of given revisionId (in seconds)
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  long timestampSeconds(String revisionId)
      throws VersionControlCommandFailedException, InterruptedException;

}
