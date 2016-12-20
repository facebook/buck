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
 * This is used if the project being built doesn't use a supported VCS.
 */
public class NoOpCmdLineInterface implements VersionControlCmdLineInterface {
  @Override
  public boolean isSupportedVersionControlSystem() {
    return false;
  }

  @Override
  public String revisionId(String name)
      throws VersionControlCommandFailedException, InterruptedException {
    return "";
  }

  @Override
  public Optional<String> revisionIdOrAbsent(String name) throws InterruptedException {
    return Optional.empty();
  }

  @Override
  public String currentRevisionId()
      throws VersionControlCommandFailedException, InterruptedException {
    return "";
  }

  @Override
  public String commonAncestor(
      String revisionIdOne,
      String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException {
    return "";
  }

  @Override
  public Optional<String> commonAncestorOrAbsent(
      String revisionOne,
      String revisionTwo) throws InterruptedException {
    return Optional.empty();
  }

  @Override
  public String diffBetweenRevisions(String baseRevision, String tipRevision)
      throws VersionControlCommandFailedException, InterruptedException {
    return "";
  }

  @Override
  public long timestampSeconds(String revisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    return 0;
  }

  @Override
  public ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableMap<String, String> bookmarksRevisionsId(ImmutableSet<String> bookmarks)
    throws InterruptedException, VersionControlCommandFailedException {
    return ImmutableMap.of();
  }

}
