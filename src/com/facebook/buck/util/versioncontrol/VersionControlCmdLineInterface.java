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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/***
 * Provides meta-data about the version control repository the project being built is using.
 */
public interface VersionControlCmdLineInterface {
  /**
   *
   * @return true if project is using version control, and we support it (i.e. hg)
   */
  boolean isSupportedVersionControlSystem();

  /**
   * @param name Bookmark name, e.g. master
   * @return Global revision ID for given name
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  String revisionId(String name) throws VersionControlCommandFailedException, InterruptedException;

  /**
   * @param name Bookmark name, e.g master
   * @return Global revision ID for given name or {@link Optional#absent} if the bookmark doesn't
   * exist or an error was encountered. If you want to handle the exception use
   * {@link #revisionId(String)}
   * @throws InterruptedException
   */
  Optional<String> revisionIdOrAbsent(String name) throws InterruptedException;

  /**
   *
   * @return Revision ID for current tip
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  String currentRevisionId() throws VersionControlCommandFailedException, InterruptedException;

  /**
   *
   * @param revisionIdOne
   * @param revisionIdTwo
   * @return Revision ID that is an ancestor for both revisionIdOne and revisionIdTwo
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  String commonAncestor(String revisionIdOne, String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException;

  /**
   * @param revisionIdOne
   * @param revisionIdTwo
   * @return Revision ID that is an ancestor for both revisionIdOne and revisionIdTwo or
   * {@link Optional#absent()} is there is no common ancestor or an error was encountered. If you
   * want to handle the error use {@link #commonAncestor(String, String)}
   * @throws InterruptedException
   */
  Optional<String> commonAncestorOrAbsent(String revisionIdOne, String revisionIdTwo)
      throws InterruptedException;

  /**
   *
   * @param revisionIdOne
   * @param revisionIdTwo
   * @return the produced diff between two revisions
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  String diffBetweenRevisions(String revisionIdOne, String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException;

  /**
   *
   * @param fromRevisionId
   * @return files changed from the given revision.
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException;

  /**
   *
   * @return a list of all the untracked of the current revision
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  ImmutableSet<String> untrackedFiles()
      throws VersionControlCommandFailedException, InterruptedException;

  /**
   *
   * @param revisionId
   * @return Unix timestamp of given revisionId (in seconds)
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  long timestampSeconds(String revisionId)
      throws VersionControlCommandFailedException, InterruptedException;

  /**
   * First the method finds the common ancestor between the tipRevisionId and revisionId.
   * Then compares that revision with the bookmarks.
   * @param tipRevisionId the revision that will be used as a tip.
   * @param revisionId  the revision that you want to check if is off the bookmarks
   * @param bookmarks the set of bookmarks that will be checked
   * @return a set of bookmarks that the revisionId is off.
   * @throws InterruptedException
   */
  ImmutableSet<String> trackedBookmarksOffRevisionId(
      String tipRevisionId,
      String revisionId,
      ImmutableSet<String> bookmarks
  ) throws InterruptedException;

  /**
   *
   * @return a map of all the bookmarks with the bookmark name as key and the revision as value.
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  ImmutableMap<String, String> allBookmarks()
      throws VersionControlCommandFailedException, InterruptedException;

}
