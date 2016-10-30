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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Optional;

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
   * @return Global revision ID for given name or {@link Optional#empty} if the bookmark doesn't
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
   * {@link Optional#empty()} is there is no common ancestor or an error was encountered. If you
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
   * @param revisionId
   * @return Unix timestamp of given revisionId (in seconds)
   * @throws VersionControlCommandFailedException
   * @throws InterruptedException
   */
  long timestampSeconds(String revisionId)
      throws VersionControlCommandFailedException, InterruptedException;

  /**
   * It receives a list of bookmarks and returns a map of bookmarks to revision ids.
   * @return a map of bookmark to revision id.
   * @throws InterruptedException
   * @throws VersionControlCommandFailedException
   */
  ImmutableMap<String, String> bookmarksRevisionsId(ImmutableSet<String> bookmarks)
      throws InterruptedException, VersionControlCommandFailedException;

}
